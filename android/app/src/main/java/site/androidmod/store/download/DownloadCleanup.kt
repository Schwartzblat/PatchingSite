package site.androidmod.store.download

import java.io.File

/**
 * Reclaims downloaded artifacts.
 *
 * Nothing else ever deletes one. [DownloadRegistry.clear] drops a map entry, not a
 * file, and the registry is memory-only, so after process death every build in
 * `filesDir/downloads` is orphaned AND invisible - the user cannot see it in the
 * app and cannot reclaim the space except by clearing app data, which also wipes
 * their token. At 38-85 MB per build that adds up fast.
 */
object DownloadCleanup {

    /**
     * Deletes every file in [directory] that no live registry entry points at, and
     * returns the ones it removed.
     *
     * Called from `Application.onCreate`, where the registry is always empty - it is
     * memory-only and the foreground service dies with the process, so a download
     * cannot be in flight across process start and "delete everything" is the right
     * answer there. The in-flight guard is what makes the function safe to call
     * anywhere else as well: a transfer in progress has no `Done` entry yet, so its
     * partial file would otherwise look exactly like an orphan.
     */
    fun pruneOrphans(directory: File): List<File> {
        if (DownloadRegistry.isAnyBusy()) return emptyList()
        val keep = DownloadRegistry.liveFiles().mapTo(mutableSetOf()) { it.stablePath() }
        val files = directory.listFiles() ?: return emptyList()
        return files.filter { file -> file.isFile && file.stablePath() !in keep && file.delete() }
    }

    /** Canonical where possible: the registry may hold a differently spelled path. */
    private fun File.stablePath(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)
}
