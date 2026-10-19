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

    /** Open the Library; nothing starts. */
    data object Library : NavRequest

    /** Open the run screen of [pkg]'s generic patch. */
    data class Generic(val pkg: String) : NavRequest
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

/**
 * The request an incoming intent carries. Null for one relaunched from Recents: the task's
 * base intent keeps its extras, and replaying them would start an update nobody just asked for.
 */
fun requestFrom(update: String?, move: Boolean, flags: Int, library: Boolean = false, generic: String? = null): NavRequest? = when {
    flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0 -> null
    update != null -> NavRequest.Update(update)
    generic != null -> NavRequest.Generic(generic)
    move -> NavRequest.Move
    library -> NavRequest.Library
    else -> null
}
