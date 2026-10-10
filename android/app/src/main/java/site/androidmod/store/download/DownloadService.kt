package site.androidmod.store.download

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
import site.androidmod.store.MainActivity
import site.androidmod.store.R
import site.androidmod.store.StoreApp
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.DownloadProgress
import site.androidmod.store.data.NotEnoughSpaceException
import site.androidmod.store.data.isAuth
import site.androidmod.store.data.needsSubscription
import site.androidmod.store.ui.notEnoughSpaceMessage
import java.io.File

/**
 * Runs one download to completion in the foreground.
 *
 * Foreground because the artifacts are 38-85 MB and the server spends minutes
 * re-signing before the first byte moves; backgrounding the app must not kill it.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // `running` is touched from the main thread (onStartCommand) and from IO
    // threads (each job's finally block), so every access goes through `lock`.
    // An unsynchronised LinkedHashMap here can drop a stopSelf - leaking the
    // service and its ongoing notification - or fire a spurious one that cancels
    // a second download that had just started.
    private val lock = Any()
    private val running = mutableMapOf<DownloadKey, Job>()

    /** The newest start id seen, so a finishing job never stops a newer one. */
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packageName = intent?.getStringExtra(EXTRA_PACKAGE) ?: return START_NOT_STICKY
        val version = intent.getStringExtra(EXTRA_VERSION) ?: return START_NOT_STICKY
        val filename = intent.getStringExtra(EXTRA_FILENAME) ?: "$packageName-$version.apk"
        val key = DownloadKey(packageName, version)
        val label = DownloadNotifications.label(intent.getStringExtra(EXTRA_NAME), key)

        startForegroundCompat(ongoing(label, DownloadNotifications.progressText(DownloadState.Queued), 0, true))

        synchronized(lock) {
            latestStartId = startId
            if (running[key]?.isActive == true) return START_NOT_STICKY
            DownloadRegistry.update(key, DownloadState.Queued)
            // Assigned under the same lock the finally block takes, so a job that
            // finishes immediately cannot remove its entry before it is stored.
            running[key] = scope.launch { run(key, filename, label) }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(key: DownloadKey, filename: String, label: String) {
        // The terminal state, for the notification that outlives the service.
        // Stays null on cancellation, which is teardown rather than a result.
        var finished: DownloadState? = null
        try {
            // Inside the try: targetFile REFUSES a server-supplied package, version
            // or suffix that would escape the downloads directory, and an escaping
            // throw here would skip the finally and leak the foreground service.
            val target = targetFile(this, key, filename)
            StoreApp.instance.container.downloads.download(key.packageName, key.version, target) { progress ->
                val state = when (progress) {
                    is DownloadProgress.Preparing -> DownloadState.Preparing(progress.fraction, progress.message)
                    is DownloadProgress.Transferring -> DownloadState.Transferring(progress.bytesRead, progress.total)
                }
                DownloadRegistry.update(key, state)
                notifyProgress(key, label, state)
            }
            finished = DownloadState.Done(target)
            DownloadRegistry.update(key, finished)
        } catch (error: CancellationException) {
            // The scope dies with the service, so a cancellation here is teardown,
            // not a failed download: reporting it as one would put a failure in
            // front of a user who did nothing wrong. Clearing the entry also stops
            // isBusy() answering true forever for a transfer that no longer exists.
            Log.i(TAG, "Download cancelled for $key")
            DownloadRegistry.clear(key)
            throw error
        } catch (error: ApiException) {
            Log.w(TAG, "Download failed for $key: ${error.message}")
            // A 401 here means the same thing it means on any other screen, and the
            // service has no view model of its own: route it through the one shared
            // path so the app lands on the login form instead of showing a banner
            // the user cannot act on.
            if (error.isAuth) StoreApp.instance.container.auth.expireSession()
            finished = DownloadState.Failed(error.message, needsSubscription = error.needsSubscription)
            DownloadRegistry.update(key, finished)
        } catch (error: NotEnoughSpaceException) {
            // Its own message: the one failure the user can fix on the phone, and
            // "could not be completed" gave them no hint that it was theirs to fix.
            Log.w(TAG, "Download for $key does not fit: ${error.message}")
            finished = DownloadState.Failed(notEnoughSpaceMessage(error))
            DownloadRegistry.update(key, finished)
        } catch (error: Throwable) {
            Log.e(TAG, "Download crashed for $key", error)
            finished = DownloadState.Failed("The download could not be completed.")
            DownloadRegistry.update(key, finished)
        } finally {
            // Before stopSelf: the ongoing notification goes with the service, and
            // this is what is left for a user who was not watching.
            finished?.let { runCatching { notifyFinished(key, label, it) } }
            val stopId = synchronized(lock) {
                running.remove(key)
                throttle.forget(key)
                // Read the newest start id inside the lock. If another download
                // starts after this, its onStartCommand raises latestStartId and
                // stopSelf(stopId) becomes a no-op, so the new transfer survives.
                if (running.isEmpty()) latestStartId else null
            }
            stopId?.let { stopSelf(it) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        // A channel of its own, at default importance: progress is background
        // noise and stays silent, but "ready to install" is the thing the user was
        // waiting for - and a user who disagrees can silence just this channel.
        manager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL_ID, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun ongoing(title: String, text: String, percent: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .build()

    /** Which progress updates are posted; see [ProgressThrottle]. */
    private val throttle = ProgressThrottle(PROGRESS_INTERVAL_MILLIS)

    /** Posts a change the user could see - another percent, another phase - at most once a second. */
    private fun notifyProgress(key: DownloadKey, label: String, state: DownloadState) {
        val (percent, indeterminate) = when (state) {
            is DownloadState.Transferring -> if (state.total > 0) {
                (state.bytesRead * 100 / state.total).toInt() to false
            } else {
                // No length: the text's byte count is the only thing that moves.
                (state.bytesRead / BYTES_PER_UNKNOWN_STEP).toInt() to true
            }
            is DownloadState.Preparing -> (state.fraction * 100).toInt() to true
            else -> 0 to true
        }
        val signature = "${state is DownloadState.Transferring}|$percent"
        if (!throttle.shouldPost(key, signature, SystemClock.elapsedRealtime())) return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            ongoing(label, DownloadNotifications.progressText(state), percent.coerceAtMost(100), indeterminate),
        )
    }

    /**
     * "Ready to install" or "could not be downloaded", left behind after the
     * foreground notification goes. One per package, so a retry replaces the
     * failure it retried. Tapping it brings the store to the front.
     */
    private fun notifyFinished(key: DownloadKey, label: String, state: DownloadState) {
        val text = DownloadNotifications.finished(label, state) ?: return
        val manager = getSystemService(NotificationManager::class.java)
        // POST_NOTIFICATIONS denied on Android 13+, or this app's notifications
        // turned off: the in-app card shows the same result, so there is nothing
        // to fall back to.
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            this,
            0,
            // The launcher's own intent, so an app already open in the background
            // is brought forward instead of stacking a second copy on top of it.
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val id = RESULT_NOTIFICATION_BASE + (key.packageName.hashCode() and RESULT_NOTIFICATION_MASK)
        // Cancelled first, so this is posted as a new notification rather than as
        // an update to the previous result: Android sheds a package's updates past
        // about five a second, and a shed "ready to install" would leave the old
        // "could not be downloaded" on screen.
        manager.cancel(id)
        manager.notify(
            id,
            NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text.title)
                .setContentText(text.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "PatchedStore"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_CHANNEL_ID = "download_results"

        // Clear of 1001 and UpdatedReceiver's 1002 whatever the package hashes to.
        private const val RESULT_NOTIFICATION_BASE = 0x10000
        private const val RESULT_NOTIFICATION_MASK = 0xFFFF
        private const val BYTES_PER_UNKNOWN_STEP = 1024L * 1024
        private const val PROGRESS_INTERVAL_MILLIS = 1_000L
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_FILENAME = "filename"
        private const val EXTRA_NAME = "name"

        /** Where a finished build lands; see [DownloadPaths.targetFile]. */
        fun targetFile(context: Context, key: DownloadKey, filename: String): File =
            DownloadPaths.targetFile(directoryFor(context), key, filename)

        fun directoryFor(context: Context): File =
            File(context.filesDir, DownloadPaths.DIRECTORY).apply { mkdirs() }

        /** [name] is the catalogue's display name, for the notifications; null falls back to the package. */
        fun start(context: Context, packageName: String, version: String, filename: String, name: String?) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_VERSION, version)
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_NAME, name)
            }
            context.startForegroundService(intent)
        }
    }
}
