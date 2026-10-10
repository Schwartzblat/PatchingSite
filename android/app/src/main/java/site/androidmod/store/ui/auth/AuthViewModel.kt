package site.androidmod.store.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import site.androidmod.store.StoreApp
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.AuthRepository
import site.androidmod.store.data.SessionRestore
import site.androidmod.store.data.User
import site.androidmod.store.data.isAuth

data class AuthState(
    val user: User? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val registered: Boolean = false,
    /**
     * Set when a stored session could not be checked at launch because the server
     * did not answer, or answered with anything but a 401 - carrying why. The
     * session is kept, so this is not the login form: AppNavigation shows a
     * "Can't reach" screen whose Retry runs [AuthViewModel.restore] again. It stays
     * set while a retry is in flight so that screen can show its progress.
     */
    val unreachable: String? = null,
    /**
     * True while the launch restore (or its retry) runs, and nothing else - it is
     * what AppNavigation shows the full-screen spinner for. Signing in and
     * registering set [loading] only: they used to look exactly like the launch
     * restore (loading, no user, no error), so the spinner replaced the login
     * form, and a failed sign-in came back to a form with its fields wiped.
     */
    val restoring: Boolean = false,
    /**
     * Good news for the login form, drawn apart from [error]: after registering,
     * where the verification link went. The server does not sign a new account
     * in, so the login form is where the user lands, and it used to say nothing.
     */
    val notice: String? = null,
    /** The Plan tab's "Resend link": in flight, and what the server said. */
    val verification: VerificationState = VerificationState(),
)

/**
 * The verify-your-email card's own state, kept apart from [AuthState.error] and
 * [AuthState.loading]: those drive the login form and the launch spinner, and a
 * resend must touch neither. Every full [AuthState] rebuild (sign-in, sign-out,
 * expiry) resets it, so one account's answer is never shown to the next.
 */
data class VerificationState(
    val sending: Boolean = false,
    /** The last resend's answer, shown inside the card; null before the first. */
    val message: String? = null,
    /** Whether [message] is a refusal, for its colour. */
    val failed: Boolean = false,
)

class AuthViewModel(
    private val auth: AuthRepository = StoreApp.instance.container.auth,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthState(restoring = true))
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        restore()
        observeExpiry()
    }

    /**
     * One route out of an expired session, for the whole app.
     *
     * Every screen that talks to the API can be told 401 once the 30-day token runs
     * out or the renewal chain hits its ceiling. Before this, each of them just
     * rendered "Authentication required!" in a banner forever: the nav shell only
     * switches to the login graph when `user` is null, and nothing set it back to
     * null. The repository owns the signal so the view models that raise it do not
     * need a reference to this one.
     */
    private fun observeExpiry() {
        viewModelScope.launch {
            auth.sessionExpired.collect { expired ->
                if (!expired) return@collect
                auth.acknowledgeExpiry()
                _state.value = AuthState(user = null, loading = false, error = SESSION_EXPIRED)
            }
        }
    }

    /** The in-flight restore, so a second tap on Retry does not start another. */
    private var restoreJob: Job? = null

    /**
     * Runs at launch, and again from the "Can't reach" screen's Retry.
     *
     * Three ways out. A restored session opens the app. A session the server
     * refused (a 401, the renewal cap included) goes to the login form with
     * [SESSION_EXPIRED], so the user knows why they are there. Anything else - no
     * network, a server mid-restart - keeps the session and sets
     * [AuthState.unreachable]. That used to drop to the login form showing
     * "Network error", which read as "you have been signed out" to someone who
     * had not been, on every launch in a tunnel.
     */
    fun restore() {
        if (restoreJob?.isActive == true) return
        restoreJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, restoring = true, error = null)
            _state.value = try {
                when (val restored = auth.restoreSession()) {
                    is SessionRestore.Restored -> AuthState(user = restored.user, loading = false)
                    SessionRestore.SignedOut -> AuthState(loading = false)
                    SessionRestore.Expired -> AuthState(loading = false, error = SESSION_EXPIRED)
                }
            } catch (error: ApiException) {
                AuthState(loading = false, unreachable = error.message)
            }
        }
    }

    fun signIn(username: String, password: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                _state.value = AuthState(user = auth.login(username, password), loading = false)
            } catch (error: ApiException) {
                _state.value = AuthState(user = null, loading = false, error = error.message)
            }
        }
    }

    fun signUp(username: String, email: String, password: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val sent = auth.register(username, email, password)
                _state.value = _state.value.copy(
                    loading = false, registered = true, notice = registeredNotice(email, sent),
                )
            } catch (error: ApiException) {
                _state.value = _state.value.copy(loading = false, error = error.message)
            }
        }
    }

    fun signOut() {
        auth.logout()
        _state.value = AuthState(user = null, loading = false)
    }

    /**
     * Called when crossing between the login and register forms. The notice goes
     * too: it answers the registration just made, and would read as stale news on
     * a second visit to either form.
     */
    fun clearError() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    /**
     * The Plan tab's "Resend link". Each answer is shown inside the card rather
     * than in the screen's error banner, next to the button that asked.
     *
     * A 409 means the address was verified after this app last read the user -
     * the link opens in a browser or a mail app, so the app is not told. The
     * server has answered for this account, so the user is marked verified at
     * once, which hides the card, and is then re-read for the rest of it. Marking
     * first matters when that read fails: otherwise the card would go on saying
     * "verify your email" above "already verified".
     */
    fun resendVerification() {
        if (_state.value.verification.sending) return
        // Set before launching so a second tap in the same frame is refused.
        _state.value = _state.value.copy(verification = VerificationState(sending = true))
        viewModelScope.launch {
            val outcome = try {
                VerificationState(message = auth.resendVerification())
            } catch (error: ApiException) {
                when {
                    error.isAuth -> {
                        // observeExpiry rebuilds the state for the login form.
                        auth.expireSession()
                        return@launch
                    }
                    error.status == HTTP_CONFLICT -> {
                        _state.value = _state.value.copy(
                            user = _state.value.user?.copy(emailVerified = true),
                        )
                        // Awaited so `sending` covers the whole exchange; the card
                        // is already hidden, so nothing waits on screen for it.
                        refreshUser().join()
                        VerificationState(message = error.message)
                    }
                    // The server's own 429 wording is its generic "Too many requests",
                    // which does not say the earlier links are still good.
                    error.status == HTTP_TOO_MANY_REQUESTS ->
                        VerificationState(message = TOO_MANY_RESENDS, failed = true)
                    // 503 carries the support address; no network reads as such.
                    else -> VerificationState(message = error.message, failed = true)
                }
            }
            _state.value = _state.value.copy(verification = outcome)
        }
    }

    /**
     * Re-reads the user while their email is unverified, and does nothing (null)
     * otherwise. The Plan tab calls it whenever it resumes: the verification link
     * is opened outside this app, so coming back to it is the only sign there is
     * that the card may be out of date. Returns the check for tests to await.
     */
    fun recheckVerification(): Job? {
        if (_state.value.user?.needsVerification != true) return null
        return refreshUser()
    }

    /** The in-flight re-read, so resumes in quick succession ask once. */
    private var refreshJob: Job? = null

    private fun refreshUser(): Job {
        refreshJob?.takeIf { it.isActive }?.let { return it }
        return viewModelScope.launch {
            try {
                val fresh = auth.me()
                // Only onto the same account: a sign-out while this was in flight
                // must not be undone by its answer.
                if (_state.value.user?.username == fresh.username) {
                    _state.value = _state.value.copy(user = fresh)
                }
            } catch (error: ApiException) {
                // A background check has nothing to say about a network blip; the
                // next resume asks again. A refused session is the exception.
                if (error.isAuth) auth.expireSession()
            }
        }.also { refreshJob = it }
    }

    /**
     * `registered` is a one-shot signal, not durable state: it exists only to tell
     * RegisterScreen to navigate once. The view model outlives navigation, so a flag
     * left set means the NEXT visit to the register screen bounces straight back to
     * login before the user can type. The screen consumes it as soon as it acts.
     */
    fun consumeRegistered() {
        _state.value = _state.value.copy(registered = false)
    }

    private companion object {
        /** One wording for a session that ended at launch and one that ended mid-use. */
        const val SESSION_EXPIRED = "Your session expired. Please sign in again."

        const val HTTP_CONFLICT = 409
        const val HTTP_TOO_MANY_REQUESTS = 429

        const val TOO_MANY_RESENDS =
            "You've asked for several links already. Check your inbox and spam folder for " +
                "the last one, or try again later."

        /**
         * What the login form says after registering. Unverified accounts can sign
         * in and browse, so none of these holds the user up; they say where the
         * link went, or that it could not go and where to send it from later.
         * Null is a server that predates verification, and promises no email.
         */
        fun registeredNotice(email: String, verificationSent: Boolean?): String = when (verificationSent) {
            true -> "Account created. We sent a verification link to $email. " +
                "Sign in now; you'll need to verify your email before you subscribe."
            false -> "Account created. Email isn't available right now, so no verification " +
                "link was sent. Sign in, and send one later from the Plan tab."
            null -> "Account created. Sign in to continue."
        }
    }
}
