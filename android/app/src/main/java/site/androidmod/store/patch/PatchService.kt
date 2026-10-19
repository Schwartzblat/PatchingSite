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
import site.androidmod.store.generic.GenericFailure
import site.androidmod.store.generic.GenericOutcome
import site.androidmod.store.generic.genericPatchState
import site.androidmod.store.generic.message
import site.androidmod.store.generic.patchFailure
import site.androidmod.store.generic.unpatchableMessage
import site.androidmod.store.move.InstallGate
import site.androidmod.store.update.StoreSelfUpdate
import java.io.File

/**
 * Runs [start], a foreground-service start; false when Android refused it. Started from the
 * background - an update request whose `latest` check finished after the user left the app -
 * startForegroundService throws ForegroundServiceStartNotAllowedException on Android 12+,
 * an IllegalStateException, as Android 11's refusal is.
 */
internal fun foregroundStartAllowed(start: () -> Unit): Boolean = try {
    start()
    true
} catch (error: IllegalStateException) {
    false
}

/**
 * Runs on-device builds in the foreground: the official WhatsApp APK is about 155 MB and its
 * download is most of a build (patching and signing took about 2 s on the emulator), so
 * leaving the app must not stop it. One job per package. An update installs from here too,
 * and may wait up to 30 minutes for the user to confirm it from a notification (UpdateInstall).
 * A generic build (any installed app) only saves the original and builds here: removing and
 * installing are the run screen's.
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

        startForegroundCompat(ongoing(displayName(pkg, givenName, mode), "Checking version…", 0, true))

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
        val name = displayName(pkg, givenName, mode)
        // The terminal notification; stays null when the user cancelled or on teardown.
        var finished: NotificationText? = null
        try {
            finished = when (mode) {
                PatchMode.GUIDE, PatchMode.UPDATE -> runBuild(pkg, name, mode)
                PatchMode.GENERIC -> runGeneric(pkg, name)
            }
        } catch (error: CancellationException) {
            // The scope dies with the service: teardown, not a failure.
            Log.i(TAG, "Patch job cancelled for $pkg")
            PatchRegistry.clear(pkg)
            throw error
        } catch (error: Throwable) {
            // A generic job's errors are logged by class only (see runGeneric).
            if (mode == PatchMode.GENERIC) Log.e(TAG, "Generic patch job crashed for $pkg: ${error.javaClass.name}")
            else Log.e(TAG, "Patch job crashed for $pkg", error)
            val reason = PatchFailure.FAILED
            val message = if (mode == PatchMode.GENERIC) GenericFailure.FAILED.message(name) else reason.message()
            PatchRegistry.update(pkg, PatchState.Failed(reason, message))
            finished = NotificationText("$name could not be patched", message)
        } finally {
            val manager = getSystemService(NotificationManager::class.java)
            // The confirm prompt is moot whichever way this ended.
            manager.cancel(resultId(pkg))
            finished?.let { runCatching { notifyFinished(pkg, it, mode) } }
            val stopId = synchronized(lock) {
                running.remove(pkg)
                throttle.forget(DownloadKey(pkg, THROTTLE_VERSION))
                if (running.isEmpty()) latestStartId else null
            }
            stopId?.let { stopSelf(it) }
        }
    }

    /** A WhatsApp build: for the guide, or an update installed from here. */
    private suspend fun runBuild(pkg: String, name: String, mode: PatchMode): NotificationText? {
        val outcome = withContext(Dispatchers.IO) {
            container.patchJob(pkg).run(
                pkg = pkg,
                finalFile = { versionName -> finalFile(pkg, versionName, mode) },
                onStage = { stage -> onStage(pkg, name, stage) },
                confirm = { titles -> confirm(pkg, name, titles) },
            )
        }
        return when (outcome) {
            is PatchOutcome.Ready -> onReady(pkg, name, mode, outcome)
            is PatchOutcome.Failed -> onFailed(pkg, name, outcome)
            PatchOutcome.Cancelled -> {
                PatchRegistry.clear(pkg)
                null
            }
        }
    }

    /** A generic build: the original saved and the patched copy built, nothing removed or installed. */
    private suspend fun runGeneric(pkg: String, name: String): NotificationText {
        val records = container.genericRecords
        val outcome = withContext(Dispatchers.IO) {
            container.genericJob(pkg).run(pkg) { stage -> onStage(pkg, name, stage) }
        }
        val versionName = withContext(Dispatchers.IO) { records.get(pkg)?.versionName }.orEmpty()
        PatchRegistry.update(pkg, genericPatchState(outcome, name, records.builtDir(pkg), versionName))
        return when (outcome) {
            GenericOutcome.Built -> NotificationText("$name is ready to install", "Tap to open Patched Store and continue.")
            is GenericOutcome.Failed -> {
                // The cause's class, never its message: nothing a generic failure says goes to the log.
                val from = outcome.cause?.let { ", from ${it.javaClass.name}" }.orEmpty()
                Log.w(TAG, "Generic patch failed for $pkg: ${outcome.reason}$from")
                afterFailure(outcome.reason.patchFailure, outcome.cause)
                NotificationText("$name could not be patched", outcome.message)
            }
            is GenericOutcome.Unpatchable -> NotificationText("$name can't be patched", unpatchableMessage(name, outcome.reasons))
        }
    }

    /**
     * From the intent, else the last catalogue that loaded; never the network nor the disk (this
     * runs on the main thread too). A generic build's caller passes the app's label.
     */
    private fun displayName(pkg: String, given: String?, mode: PatchMode): String = when (mode) {
        PatchMode.GENERIC -> given ?: pkg
        PatchMode.GUIDE, PatchMode.UPDATE -> given ?: container.appNames[pkg] ?: if (pkg == WHATSAPP) "WhatsApp" else pkg
    }

    private fun finalFile(pkg: String, versionName: String, mode: PatchMode): File {
        val key = DownloadKey(pkg, versionName)
        return when (mode) {
            PatchMode.GUIDE -> DownloadPaths.targetFile(DownloadService.directoryFor(this), key, "apk")
            PatchMode.UPDATE -> DownloadPaths.targetFile(File(filesDir, READY_DIRECTORY).apply { mkdirs() }, key, "apk")
            PatchMode.GENERIC -> throw IllegalStateException("A generic build has no single final file")
        }
    }

    /** May run on a background thread (download progress, signing). */
    private fun onStage(pkg: String, name: String, stage: PatchStage) {
        PatchRegistry.update(pkg, PatchState.Running(stage))
        val progress = when (stage) {
            is PatchStage.Downloading -> stage.bytesRead to stage.total
            is PatchStage.SavingOriginal -> stage.bytesDone to stage.total
            else -> null
        }
        val percent = progress?.takeIf { (_, total) -> total > 0 }
            ?.let { (done, total) -> (done * 100 / total).toInt().coerceIn(0, 100) }
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
            PatchMode.GENERIC -> throw IllegalStateException("A generic build is handled by runGeneric")
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
        afterFailure(failed.reason, failed.cause)
        return NotificationText("$name could not be patched", failed.message)
    }

    /** What a failure of either job sets off: a real 401 signs out, a store too old updates itself. */
    private suspend fun afterFailure(reason: PatchFailure, cause: Throwable?) {
        if (reason == PatchFailure.SIGNED_OUT && cause is ApiException && cause.isAuth) {
            // Same meaning as on any screen: land on the login form.
            container.auth.expireSession()
        }
        if (reason == PatchFailure.STORE_UPDATE_REQUIRED) {
            try {
                StoreSelfUpdate.run(container)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Store self-update failed", error)
            }
        }
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

    /** One result notification per package; a new one replaces the last. A generic one opens that app's run screen. */
    private fun notifyFinished(pkg: String, text: NotificationText, mode: PatchMode) =
        post(pkg, text, if (mode == PatchMode.GENERIC) openGeneric(pkg) else openApp())

    private fun openGeneric(pkg: String): PendingIntent = PendingIntent.getActivity(
        this,
        resultId(pkg),
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_GENERIC, pkg),
        // UPDATE_CURRENT: intents that differ only in their extras match, so an older one would be reused as it was.
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

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

        /** On MainActivity's intent from a generic build's notification: the package whose run screen to open. */
        const val EXTRA_OPEN_GENERIC = "open_generic"

        /**
         * [name] is the catalogue's display name for the notifications; null uses the last
         * catalogue's name, then WhatsApp/the package; a generic build passes its record's label
         * (null shows the package). False when Android refused the start
         * because the app is not in the foreground (see [foregroundStartAllowed]): nothing
         * runs, and the caller says so.
         */
        fun start(context: Context, pkg: String, mode: PatchMode, name: String? = null): Boolean {
            val intent = Intent(context, PatchService::class.java).apply {
                putExtra(EXTRA_PACKAGE, pkg)
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_NAME, name)
            }
            return foregroundStartAllowed { context.startForegroundService(intent) }
        }
    }
}
