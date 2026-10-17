package site.androidmod.store.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Somewhere a notification tap or the patched app's update button wants the store to go. */
sealed interface NavRequest {
    /** Update [pkg]: open the Library and start (or explain) its update. */
    data class Update(val pkg: String) : NavRequest

    /** Open the one-time move guide. */
    data object Move : NavRequest
}

/**
 * One pending request, held until the navigation host can act on it - which, signed out,
 * is only after sign-in. Process-wide because MainActivity receives the intent and the
 * Compose tree consumes it.
 */
object NavigationRequests {
    private val _pending = MutableStateFlow<NavRequest?>(null)
    val pending: StateFlow<NavRequest?> = _pending.asStateFlow()

    fun request(request: NavRequest) {
        _pending.value = request
    }

    fun consume() {
        _pending.value = null
    }
}
