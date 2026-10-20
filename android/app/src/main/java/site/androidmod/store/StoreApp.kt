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
        // The OBB probe's process (install.ObbProbeProvider) needs none of the store, and setting it up
        // there would wipe a running job's work and downloads, and register a second watcher.
        if (!isMainProcess(Application.getProcessName(), packageName)) return
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
     * Empties `filesDir/patch/work` and `filesDir/patch/ready`: the process just started, so no
     * patch job can be running, and whatever an earlier process left there is an orphan - up to
     * ~3x an APK of work files, or an update build whose install died with that process. Off the
     * main thread.
     */
    private fun wipePatchWork() {
        Thread({
            for (directory in listOf("patch/work", "patch/ready")) {
                runCatching { File(filesDir, directory).deleteRecursively() }
                    .onFailure { Log.w(TAG, "Could not wipe $directory", it) }
            }
        }, "patch-work-wipe").apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "PatchedStore"

        lateinit var instance: StoreApp
            private set
    }
}

/**
 * Whether [processName] is the store's main process, named after its package; the OBB probe's is
 * `<package>:obbprobe`. A missing name counts as the main process: Android names a process before
 * Application.onCreate, and a store that can't set itself up would be worse.
 */
internal fun isMainProcess(processName: String?, packageName: String): Boolean =
    processName == null || processName == packageName
