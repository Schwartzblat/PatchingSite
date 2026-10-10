package site.androidmod.store.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class DownloadKey(val packageName: String, val version: String)

sealed interface DownloadState {
    data object Queued : DownloadState
    data class Preparing(val fraction: Double, val message: String?) : DownloadState
    data class Transferring(val bytesRead: Long, val total: Long) : DownloadState
    data class Done(val file: File) : DownloadState
    /**
     * [needsSubscription] carries the 402 through from the server. Without it the
     * UI cannot tell a paywall from a generic failure, and the only thing it can
     * offer for either is "Retry" - which, for a user with no subscription, fails
     * again forever with nowhere to go.
     */
    data class Failed(val message: String, val needsSubscription: Boolean = false) : DownloadState
}

/**
 * Process-wide download state.
 *
 * A singleton rather than ViewModel state on purpose: these transfers outlive the
 * screen that started them, and the service that runs them has no ViewModel.
 */
object DownloadRegistry {

    private val _downloads = MutableStateFlow<Map<DownloadKey, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<DownloadKey, DownloadState>> = _downloads.asStateFlow()

    /**
     * `update {}`, not `value = value + ...`. The latter is a read-modify-write, and
     * these callbacks arrive from Dispatchers.IO threads - one per concurrent
     * download - so the losing writer's state is silently dropped. For a repeatedly
     * refreshing state that self-heals on the next tick; for a TERMINAL Done or
     * Failed it is permanent, stranding a finished download whose file is already
     * on disk behind a button that never flips to Install.
     */
    fun update(key: DownloadKey, state: DownloadState) {
        _downloads.update { it + (key to state) }
    }

    fun clear(key: DownloadKey) {
        _downloads.update { it - key }
    }

    fun fileFor(key: DownloadKey): File? = (_downloads.value[key] as? DownloadState.Done)?.file

    fun isBusy(key: DownloadKey): Boolean = when (_downloads.value[key]) {
        is DownloadState.Queued, is DownloadState.Preparing, is DownloadState.Transferring -> true
        else -> false
    }

    /** True while any transfer is still running; see [DownloadCleanup.pruneOrphans]. */
    fun isAnyBusy(): Boolean = _downloads.value.keys.any(::isBusy)

    /**
     * Every artifact a finished download is still holding on to. Anything else in
     * the downloads directory is an orphan nothing can reach.
     */
    fun liveFiles(): Set<File> =
        _downloads.value.values.filterIsInstance<DownloadState.Done>().mapTo(mutableSetOf()) { it.file }

    /** Tests only. */
    fun reset() {
        _downloads.value = emptyMap()
    }
}
