package site.androidmod.store.generic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The generic ad remover's server status, kept so screens can show the last value offline. */
class GenericRepository(private val api: GenericApi, initial: GenericStatus? = null) {

    private val state = MutableStateFlow(initial)

    /** The last status read, null before the first (or [initial]). */
    val status: StateFlow<GenericStatus?> = state.asStateFlow()

    /** On failure keeps the last value and rethrows. */
    suspend fun refreshStatus(): GenericStatus = api.status().also { state.value = it }
}
