package site.androidmod.store.patch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.androidmod.store.Container
import site.androidmod.store.MainActivity
import site.androidmod.store.R
import site.androidmod.store.StoreApp
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.isAuth
import site.androidmod.store.download.DownloadKey
import site.androidmod.store.download.DownloadPaths
import site.androidmod.store.download.DownloadRegistry
import site.androidmod.store.download.DownloadService
import site.androidmod.store.download.DownloadState
import site.androidmod.store.download.NotificationText
import site.androidmod.store.download.ProgressThrottle
import site.androidmod.store.move.InstallGate
import site.androidmod.store.update.StoreSelfUpdate
import java.io.File

/**
 * Runs on-device builds in the foreground: the official APK is ~90 MB and patching takes
 * minutes, so backgrounding the app must not kill it. One job per package.
 */
class PatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Touched from the main thread (onStartCommand) and IO threads (each job's finally).
    private val lock = Any()
    private val running = mutableMapOf<String, Job>()
    private var latestStartId = 0

    private val throttle = ProgressThrottle(PROGRESS_INTERVAL_MILLIS)

    private val container: Container get() = StoreApp.instance.container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: return START_NOT_STICKY
        val mode = intent.getStringExtra(EXTRA_MODE)?.let { name -> PatchMode.entries.firstOrNull { it.name == name } }
            ?: return START_NOT_STICKY
        val givenName = intent.getStringExtra(EXTRA_NAME)

        startForegroundCompat(ongoing(displayName(pkg, givenName), "Checking version…", 0, true))

        synchronized(lock) {
            latestStartId = startId
            if (running[pkg]?.isActive == true || PatchRegistry.isBusy(pkg)) {
                // Nothing of ours is running: do not leave an empty foreground service behind.
                if (running.isEmpty()) stopSelf(startId)
                return START_NOT_STICKY
            }
            PatchRegistry.update(pkg, PatchState.Running(PatchStage.CheckingVersion))
            // Stored under the lock the finally block takes, so an instant finish cannot
            // remove its entry before it exists.
            running[pkg] = scope.launch { run(pkg, mode, givenName) }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(pkg: String, mode: PatchMode, givenName: String?) {
        val name = displayName(pkg, givenName)
        // The terminal notification; stays null when the user cancelled or on teardown.
        var finished: NotificationText? = null
        try {
            val outcome = withContext(Dispatchers.IO) {
                container.patchJob(pkg).run(
                    pkg = pkg,
                    finalFile = { versionName -> finalFile(pkg, versionName, mode) },
                    onStage = { stage -> onStage(pkg, name, stage) },
                    confirm = { titles -> confirm(pkg, name, titles) },
                )
            }
            finished = when (outcome) {
                is PatchOutcome.Ready -> onReady(pkg, name, mode, outcome)
                is PatchOutcome.Failed -> onFailed(pkg, name, outcome)
                PatchOutcome.Cancelled -> {
                    PatchRegistry.clear(pkg)
                    null
                }
            }
        } catch (error: CancellationException) {
            // The scope dies with the service: teardown, not a failure.
            Log.i(TAG, "Patch job cancelled for $pkg")
            PatchRegistry.clear(pkg)
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Patch job crashed for $pkg", error)
            val reason = PatchFailure.FAILED
            PatchRegistry.update(pkg, PatchState.Failed(reason, reason.message()))
            finished = NotificationText("$name could not be patched", reason.message())
        } finally {
            val manager = getSystemService(NotificationManager::class.java)
            // The confirm prompt is moot whichever way this ended.
            manager.cancel(resultId(pkg))
            finished?.let { runCatching { notifyFinished(pkg, it) } }
            val stopId = synchronized(lock) {
                running.remove(pkg)
                throttle.forget(DownloadKey(pkg, THROTTLE_VERSION))
                if (running.isEmpty()) latestStartId else null
            }
            stopId?.let { stopSelf(it) }
        }
    }

    /** From the intent, else the last catalogue that loaded; never the network. */
    private fun displayName(pkg: String, given: String?): String =
        given ?: container.appNames[pkg] ?: if (pkg == WHATSAPP) "WhatsApp" else pkg

    private fun finalFile(pkg: String, versionName: String, mode: PatchMode): File {
        val key = DownloadKey(pkg, versionName)
        return when (mode) {
            PatchMode.GUIDE -> DownloadPaths.targetFile(DownloadService.directoryFor(this), key, "apk")
            PatchMode.UPDATE -> DownloadPaths.targetFile(File(filesDir, READY_DIRECTORY).apply { mkdirs() }, key, "apk")
        }
    }

    /** May run on a background thread (download progress, signing). */
    private fun onStage(pkg: String, name: String, stage: PatchStage) {
        PatchRegistry.update(pkg, PatchState.Running(stage))
        val percent = (stage as? PatchStage.Downloading)?.takeIf { it.total > 0 }
            ?.let { (it.bytesRead * 100 / it.total).toInt().coerceIn(0, 100) }
        val signature = "${stage::class.simpleName}|$percent"
        if (!throttle.shouldPost(DownloadKey(pkg, THROTTLE_VERSION), signature, SystemClock.elapsedRealtime())) return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            ongoing(name, PatchNotifications.progressText(name, stage), percent ?: 0, percent == null),
        )
    }

    /** Never throws: [PatchRegistry.awaitAnswer] answers false when the wait is cancelled or dismissed. */
    private suspend fun confirm(pkg: String, name: String, titles: List<String>): Boolean {
        val proceed = PatchRegistry.awaitAnswer(pkg) {
            PatchRegistry.update(pkg, PatchState.AwaitingConfirmation(titles))
            runCatching { notifyConfirm(pkg, name) }
        }
        if (proceed) PatchRegistry.update(pkg, PatchState.Running(PatchStage.Patching))
        getSystemService(NotificationManager::class.java).cancel(resultId(pkg))
        return proceed
    }

    private suspend fun onReady(pkg: String, name: String, mode: PatchMode, outcome: PatchOutcome.Ready): NotificationText {
        when (mode) {
            PatchMode.GUIDE -> {
                DownloadRegistry.update(DownloadKey(pkg, outcome.versionName), DownloadState.Done(outcome.file))
                PatchRegistry.update(pkg, PatchState.Ready(outcome.file, outcome.versionName))
                return NotificationText("$name is ready to install", "Tap to open Patched Store and install it.")
            }
            PatchMode.UPDATE -> {
                PatchRegistry.update(pkg, PatchState.Installing)
                val failure = try {
                    installUpdate(pkg, name, outcome)
                } finally {
                    // Only now: installUpdate returns once the outcome is known - installed, a
                    // definitive failure, or no confirmation within UpdateInstall's 30 minutes.
                    withContext(Dispatchers.IO) { outcome.file.delete() }
                }
                if (failure == null) {
                    PatchRegistry.update(pkg, PatchState.Installed)
                    return NotificationText("$name updated", "Tap to open Patched Store.")
                }
                PatchRegistry.update(pkg, PatchState.Failed(PatchFailure.FAILED, failure))
                return NotificationText("$name could not be updated", failure)
            }
        }
    }

    /**
     * The failure message, or null when the update installed. When Android asks for the
     * user's OK, a "Tap to install" notification carries its dialog (see [UpdateInstall]).
     */
    private suspend fun installUpdate(pkg: String, name: String, outcome: PatchOutcome.Ready): String? {
        if (pkg == WHATSAPP) {
            // Puts a parked WhatsApp backup back before the update replaces the app.
            val gate = withContext(Dispatchers.IO) {
                container.move.beforeInstall(outcome.file, outcome.versionName) { }
            }
            if (gate is InstallGate.Refused) return gate.message
        }
        // On the service's IO scope: installer() may ask `su` (debug builds only).
        val installer = container.installer()
        return UpdateInstall.run(
            install = { confirmation -> installer.install(outcome.file, confirmation) },
            prompt = { confirm -> notifyInstall(pkg, name, outcome.versionName, confirm) },
            // The session is over, so its dialog is moot; run's finally posts the result in this slot.
            clearPrompt = { getSystemService(NotificationManager::class.java).cancel(resultId(pkg)) },
        )
    }

    private suspend fun onFailed(pkg: String, name: String, failed: PatchOutcome.Failed): NotificationText {
        // The engine's and the server's messages carry no secrets; the token, password and key are never in a cause.
        Log.w(TAG, "Patch failed for $pkg: ${failed.reason}", failed.cause)
        PatchRegistry.update(pkg, PatchState.Failed(failed.reason, failed.message))
        val cause = failed.cause
        if (failed.reason == PatchFailure.SIGNED_OUT && cause is ApiException && cause.isAuth) {
            // Same meaning as on any screen: land on the login form.
            container.auth.expireSession()
        }
        if (failed.reason == PatchFailure.STORE_UPDATE_REQUIRED) {
            try {
                StoreSelfUpdate.run(container)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Store self-update failed", error)
            }
        }
        return NotificationText("$name could not be patched", failed.message)
    }

    // Android 15+ caps dataSync foreground time; the service must stop within seconds of this call
    // or the system crashes the app. Cancelling the jobs clears their state like any teardown.
    override fun onTimeout(startId: Int, fgsType: Int) = stopJobs()

    override fun onTimeout(startId: Int) = stopJobs()

    private fun stopJobs() {
        Log.w(TAG, "Foreground time limit reached; stopping patch jobs")
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        synchronized(lock) { running.keys.toList() }.forEach { PatchRegistry.clear(it) }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        // The same channels DownloadService creates; creating one twice is a no-op.
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL_ID, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun ongoing(title: String, text: String, percent: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        // The launcher's own intent, so an app already open is brought forward.
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notifyConfirm(pkg: String, name: String) = post(pkg, PatchNotifications.confirmText(name))

    /**
     * Android wants the user's OK for the update ([confirm] is its dialog), and starting the
     * dialog from the background is blocked: a tap here opens it. It stays until the session
     * ends (UpdateInstall's clearPrompt), so a dialog backed out of can be opened again.
     * Called on the main thread by InstallReceiver.
     */
    private fun notifyInstall(pkg: String, name: String, versionName: String, confirm: Intent) {
        // UPDATE_CURRENT: confirm intents differ only in their extras (the session), which
        // PendingIntent matching ignores, so an older one would otherwise be reused as it was.
        val open = PendingIntent.getActivity(
            this,
            resultId(pkg),
            confirm,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            pkg,
            PatchNotifications.installText(name, versionName),
            open,
            autoCancel = false,
            priority = NotificationCompat.PRIORITY_HIGH,
        )
    }

    /** One result notification per package; a new one replaces the last. */
    private fun notifyFinished(pkg: String, text: NotificationText) = post(pkg, text)

    private fun post(
        pkg: String,
        text: NotificationText,
        contentIntent: PendingIntent = openApp(),
        autoCancel: Boolean = true,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT,
    ) {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val id = resultId(pkg)
        // Cancelled first so this is a new notification, not an update Android may shed.
        manager.cancel(id)
        manager.notify(
            id,
            NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text.title)
                .setContentText(text.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
                .setPriority(priority)
                .setContentIntent(contentIntent)
                .setAutoCancel(autoCancel)
                .build(),
        )
    }

    private fun resultId(pkg: String) = RESULT_NOTIFICATION_BASE + (pkg.hashCode() and RESULT_NOTIFICATION_MASK)

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "PatchedStore"
        private const val CHANNEL_ID = "downloads"
        private const val RESULT_CHANNEL_ID = "download_results"

        // Clear of DownloadService's 1001 and UpdatedReceiver's 1002.
        private const val NOTIFICATION_ID = 1003
        private const val RESULT_NOTIFICATION_BASE = 0x10000
        private const val RESULT_NOTIFICATION_MASK = 0xFFFF
        private const val PROGRESS_INTERVAL_MILLIS = 1_000L
        private const val THROTTLE_VERSION = "patch"
        private const val READY_DIRECTORY = "patch/ready"
        private const val WHATSAPP = "com.whatsapp"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_NAME = "name"

        /** [name] is the catalogue's display name for the notifications; null uses the last catalogue's name, then WhatsApp/the package. */
        fun start(context: Context, pkg: String, mode: PatchMode, name: String? = null) {
            val intent = Intent(context, PatchService::class.java).apply {
                putExtra(EXTRA_PACKAGE, pkg)
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_NAME, name)
            }
            context.startForegroundService(intent)
        }
    }
}
