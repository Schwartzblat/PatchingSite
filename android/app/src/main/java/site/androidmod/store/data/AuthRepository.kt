package site.androidmod.store.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** What [AuthRepository.restoreSession] found at launch. */
sealed interface SessionRestore {
    data class Restored(val user: User) : SessionRestore

    /** Nothing stored: a first launch, or the user signed out. Nothing to explain. */
    data object SignedOut : SessionRestore

    /**
     * A stored session the server refused with a 401: past the 90-day renewal cap
     * (the server will not renew a chain that old), idle past its 30-day expiry, or
     * signed with a key the server no longer uses. Distinct from [SignedOut]
     * because this user did not sign out, and the login form has to tell them so -
     * both used to come back as the same null, and the app dropped them on the
     * login form without a word.
     */
    data object Expired : SessionRestore
}

class AuthRepository(
    private val api: ApiClient,
    private val tokens: TokenHolder,
    /**
     * Emptied with the token. The Plan tab paints its first frame from this cache,
     * so a snapshot left behind by the previous account would show that account's
     * plan to whoever signs in next.
     */
    private val subscriptions: SubscriptionCache = SubscriptionCache.None,
    /** Per-account state held elsewhere (the cached signing key) goes here when a session ends. */
    private val onSignedOut: () -> Unit = {},
) {
    val isSignedIn: Boolean get() = tokens.token != null

    private val _sessionExpired = MutableStateFlow(false)

    /**
     * Raised when any screen is told 401 mid-session.
     *
     * A StateFlow rather than an event channel so a collector that attaches late
     * still sees it - and AuthViewModel, the only collector, is the one place that
     * can put the app back on the login screen. The alternative, every view model
     * holding a reference to that view model, would mean four screens each deciding
     * for themselves what an expired session looks like.
     */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    /**
     * The token is gone or no longer accepted: drop it and ask for a sign-in.
     *
     * Clearing the token matters as much as the signal. Leaving a rejected token in
     * EncryptedSharedPreferences means the next launch tries to restore with it,
     * and every screen keeps showing "Authentication required!" with no way back to
     * the login form except Settings -> Sign out.
     */
    fun expireSession() {
        endSession()
        _sessionExpired.value = true
    }

    /** Called once the app has acted on [sessionExpired]. */
    fun acknowledgeExpiry() {
        _sessionExpired.value = false
    }

    suspend fun login(username: String, password: String): User {
        val body = api.json.encodeToString(LoginRequest(username, password))
        val response: TokenResponse = api.decode(api.post("/api/auth/token", body))
        tokens.token = response.accessToken
        // Whoever was signed in before is not who is signed in now.
        subscriptions.clear()
        _sessionExpired.value = false
        return try {
            me()
        } catch (error: Throwable) {
            // A token we cannot use is worse than none: it would make the app look
            // signed in and fail on every screen.
            endSession()
            throw error
        }
    }

    /**
     * Creates the account and returns whether the server emailed a verification
     * link: true, false (it could not - the user can resend from the Plan tab),
     * or null from a server that predates verification.
     *
     * The body is read leniently, unlike everywhere else. By the time it arrives
     * the account exists, so failing on a body this app cannot parse would tell
     * the user to try again, and the retry would be refused as a taken username.
     */
    suspend fun register(username: String, email: String, password: String): Boolean? {
        val body = api.json.encodeToString(RegisterRequest(username, email, password))
        val response = api.post("/api/register", body)
        return runCatching { api.json.decodeFromString<RegisterResponse>(response) }.getOrNull()?.verificationSent
    }

    /**
     * Asks for a fresh verification link for the signed-in account and returns
     * the server's confirmation, which names the address. Refusals arrive as
     * [ApiException]: 409 already verified, 429 asked too often, 503 no email.
     */
    suspend fun resendVerification(): String {
        val response = api.post("/api/auth/resend-verification")
        return runCatching { api.json.decodeFromString<MessageResponse>(response) }.getOrNull()?.message
            ?: "We sent a new verification link."
    }

    /**
     * Changes the signed-in account's password and returns the server's
     * confirmation. The token is untouched: the server does not bind sessions to
     * the password, so this one (and every other) stays signed in. Refusals arrive
     * as [ApiException]: 400 a wrong current password (never 401, which would read
     * as a dead session), 409 changed elsewhere meanwhile, 429 too many attempts.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): String {
        val body = api.json.encodeToString(ChangePasswordRequest(currentPassword, newPassword))
        val response = api.post("/api/users/me/password", body)
        return runCatching { api.json.decodeFromString<MessageResponse>(response) }.getOrNull()?.message
            ?: "Your password has been changed."
    }

    suspend fun me(): User = api.decode(api.get("/api/users/me"))

    /**
     * Launch-time session restore. Renewing first both slides the 30-day expiry and
     * refreshes the customer_id claim, which checkout writes after the token was minted.
     *
     * Only a 401 clears the token, and it is reported as [SessionRestore.Expired]
     * rather than as "signed out", so the login form can say why the user is
     * looking at it. Everything else - no network, a 5xx while the server restarts -
     * propagates untouched with the token kept: signing a user out because their
     * train went into a tunnel is not acceptable.
     */
    suspend fun restoreSession(): SessionRestore = withContext(Dispatchers.IO) {
        // On IO because this is the app's first read of the token, and that read
        // opens the encrypted store: a keystore round trip, or on a damaged
        // keystore a reset and key generation, which must not stall the first frame.
        if (tokens.token == null) return@withContext SessionRestore.SignedOut
        try {
            val renewed: TokenResponse = api.decode(api.post("/api/auth/renew"))
            tokens.token = renewed.accessToken
            // Inside the try: a token the server refuses here, straight after
            // renewing it, is just as dead, and must not survive to the next launch.
            SessionRestore.Restored(me())
        } catch (error: ApiException) {
            if (!error.isAuth) throw error
            endSession()
            SessionRestore.Expired
        }
    }

    fun logout() {
        endSession()
        _sessionExpired.value = false
    }

    /**
     * Every path that ends a session goes through here, so per-account state cannot
     * be forgotten in one of them and survive into the next sign-in.
     */
    private fun endSession() {
        tokens.token = null
        subscriptions.clear()
        onSignedOut()
    }
}
