package site.androidmod.store

import android.app.Application
import android.util.Log
import site.androidmod.store.download.DownloadCleanup
import site.androidmod.store.download.DownloadPaths
import site.androidmod.store.move.WhatsAppInstallWatcher
import site.androidmod.store.patch.UpdateWorker
import java.io.File

class StoreApp : Application() {

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = Container(this)
        // For the life of the process: the Play Store installing WhatsApp is the
        // moment a parked backup has to go back. See WhatsAppInstallWatcher.
        WhatsAppInstallWatcher.register(this, container.move)
        pruneOrphanedDownloads()
        wipePatchWork()
        UpdateWorker.schedule(this)
    }

    /**
     * Reclaim artifacts left behind by an earlier process.
     *
     * [site.androidmod.store.download.DownloadRegistry] is memory-only and the
     * foreground service dies with the process, so nothing can be in flight here and
     * every file in the downloads directory is unreachable from the UI. Off the main
     * thread: this is file I/O over potentially hundreds of megabytes, and
     * `onCreate` runs before the first frame.
     */
    private fun pruneOrphanedDownloads() {
        Thread({
            val directory = File(filesDir, DownloadPaths.DIRECTORY)
            runCatching { DownloadCleanup.pruneOrphans(directory) }
                .onSuccess { removed ->
                    if (removed.isNotEmpty()) Log.i(TAG, "Reclaimed ${removed.size} orphaned download(s)")
                }
                .onFailure { Log.w(TAG, "Could not prune orphaned downloads", it) }
        }, "download-prune").apply { isDaemon = true }.start()
    }

    /**
     * Empties `filesDir/patch/work`: the process just started, so no patch job can be running
     * and whatever an earlier process left there is an orphan of up to ~3x an APK. Off the main thread.
     */
    private fun wipePatchWork() {
        Thread({
            runCatching { File(filesDir, "patch/work").deleteRecursively() }
                .onFailure { Log.w(TAG, "Could not wipe the patch work directory", it) }
        }, "patch-work-wipe").apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "PatchedStore"

        lateinit var instance: StoreApp
            private set
    }
}
