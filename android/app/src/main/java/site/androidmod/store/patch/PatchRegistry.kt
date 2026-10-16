package site.androidmod.store.patch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** What the screens show for one package's on-device build. */
sealed interface PatchState {
    data class Running(val stage: PatchStage) : PatchState

    /** Some visible features are off in this version; the user continues or cancels. */
    data class AwaitingConfirmation(val featureTitles: List<String>) : PatchState

    /** A guide build, handed over at [file], waiting for the user to install it. */
    data class Ready(val file: File, val versionName: String) : PatchState
    data object Installing : PatchState
    data object Installed : PatchState
    data class Failed(val reason: PatchFailure, val message: String) : PatchState
}

/**
 * Process-wide patch state, like DownloadRegistry: builds outlive the screen that
 * started them, and the service that runs them has no view model.
 */
object PatchRegistry {

    private val _states = MutableStateFlow<Map<String, PatchState>>(emptyMap())
    val states: StateFlow<Map<String, PatchState>> = _states.asStateFlow()

    // Guarded by `lock`; at most one wait per package (one job per package).
    private val lock = Any()
    private val waiting = HashMap<String, CompletableDeferred<Boolean>>()

    fun update(pkg: String, state: PatchState) {
        _states.update { it + (pkg to state) }
    }

    /** Forgets [pkg]; a job waiting for its answer is told no. */
    fun clear(pkg: String) {
        _states.update { it - pkg }
        synchronized(lock) { waiting.remove(pkg) }?.complete(false)
    }

    fun isBusy(pkg: String): Boolean = when (_states.value[pkg]) {
        is PatchState.Running, is PatchState.AwaitingConfirmation, PatchState.Installing -> true
        else -> false
    }

    /**
     * Waits for [answer] on [pkg]; false is also what a dismissed ([clear]) or cancelled wait
     * returns, so the job's confirm callback never throws. [onWaiting] runs once the wait is
     * registered (it publishes AwaitingConfirmation), so an answer can never arrive before it.
     */
    suspend fun awaitAnswer(pkg: String, onWaiting: () -> Unit = {}): Boolean {
        val answer = CompletableDeferred<Boolean>()
        synchronized(lock) { waiting.put(pkg, answer) }?.complete(false)
        try {
            onWaiting()
            return answer.await()
        } catch (cancelled: CancellationException) {
            return false
        } finally {
            synchronized(lock) { if (waiting[pkg] === answer) waiting.remove(pkg) }
        }
    }

    /** The user's choice for the job waiting on [pkg]; ignored when none is. */
    fun answer(pkg: String, proceed: Boolean) {
        synchronized(lock) { waiting.remove(pkg) }?.complete(proceed)
    }

    /** Tests only. */
    fun reset() {
        _states.value = emptyMap()
        val pending = synchronized(lock) { waiting.values.toList().also { waiting.clear() } }
        pending.forEach { it.complete(false) }
    }
}
