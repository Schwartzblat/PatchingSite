package site.androidmod.store.download

import site.androidmod.store.ui.formatBytes

/** A notification's two lines. */
internal data class NotificationText(val title: String, val body: String)

/**
 * Which progress updates reach the ongoing notification.
 *
 * Progress arrives once per 64 KB read - over a thousand times for an 85 MB
 * build - and posting each one achieves nothing but Android shedding the flood:
 * past about five updates a second from one package it drops them, and the one
 * dropped can be the one that mattered. So an update is posted only when it
 * shows something new for its download ([signature] differs from what that
 * download last posted) and at least [minIntervalMillis] after the previous post.
 * Per download, because two running at once alternate - a single shared "last
 * posted" saw every update from each as new.
 */
internal class ProgressThrottle(private val minIntervalMillis: Long) {
    private val lastPosted = HashMap<DownloadKey, String>()
    private var lastPostAt: Long? = null

    @Synchronized
    fun shouldPost(key: DownloadKey, signature: String, nowMillis: Long): Boolean {
        if (lastPosted[key] == signature) return false
        lastPostAt?.let { if (nowMillis - it < minIntervalMillis) return false }
        lastPosted[key] = signature
        lastPostAt = nowMillis
        return true
    }

    @Synchronized
    fun forget(key: DownloadKey) {
        lastPosted.remove(key)
    }
}

/**
 * The words on DownloadService's notifications, kept apart from the service so
 * they can be tested without an Android runtime.
 */
internal object DownloadNotifications {

    /**
     * "Moovit 5.201.0.1808". The name comes from the catalogue the user picked the
     * build from, and DownloadService is handed it with the download: the service
     * has no view model of its own, and a copy of the catalogue's names in this
     * client would be a fourth place to keep in step with server/config.py. A
     * missing name - a caller that had none - falls back to the package, which is
     * what every notification said before.
     */
    fun label(name: String?, key: DownloadKey): String =
        "${name?.takeIf { it.isNotBlank() } ?: key.packageName} ${key.version}"

    /** The ongoing notification's body. Server progress messages are left to the in-app card. */
    fun progressText(state: DownloadState): String = when (state) {
        is DownloadState.Transferring -> if (state.total > 0) {
            "Downloading ${formatBytes(state.bytesRead)} of ${formatBytes(state.total)}"
        } else {
            "Downloading ${formatBytes(state.bytesRead)}"
        }
        else -> "Preparing..."
    }

    /**
     * What to tell someone who is not looking at the app when a download ends, or
     * null while it is still running. The ongoing notification goes away with the
     * foreground service, so without this a download that finished - or failed -
     * while the user was elsewhere left no trace outside the app.
     */
    fun finished(label: String, state: DownloadState): NotificationText? = when (state) {
        is DownloadState.Done -> NotificationText(
            "$label is ready to install",
            "Tap to open Patched Store and install it.",
        )
        is DownloadState.Failed -> NotificationText("$label could not be downloaded", state.message)
        else -> null
    }
}
