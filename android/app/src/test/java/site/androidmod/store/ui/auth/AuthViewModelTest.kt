package site.androidmod.store.ui.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import site.androidmod.store.data.ApiClient
import site.androidmod.store.data.AuthRepository
import site.androidmod.store.data.TokenHolder
import java.util.concurrent.TimeUnit

/** The launch-time routing: signed in, signed out, expired, or unreachable. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private lateinit var server: MockWebServer
    private val tokens = object : TokenHolder {
        override var token: String? = null
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(url: String = server.url("/").toString().trimEnd('/')) = AuthViewModel(
        AuthRepository(ApiClient(OkHttpClient(), baseUrl = { url }, token = { tokens.token }), tokens)
    )

    /**
     * Waits out the network work the view model does off the test dispatcher.
     *
     * This waits for the state, not for the coroutine that published it. Under the
     * UnconfinedTestDispatcher from [setUp] that coroutine may still be running on
     * the IO thread when this returns; see [confineToTestThread] before calling
     * [AuthViewModel.restore] again after it.
     */
    private suspend fun AuthViewModel.settled(): AuthState = state.first { !it.loading }

    /**
     * Runs the view model's coroutines on the test's own thread, as Main does in the
     * app. Call it first, before building the view model, in any test that retries.
     *
     * An UnconfinedTestDispatcher never dispatches, so when a restore comes back from
     * Dispatchers.IO it carries on on the IO thread. There it publishes its outcome,
     * which wakes the test on its own thread, and only afterwards does its Job finish.
     * A restore() called in between is refused by `restoreJob.isActive` as a double
     * tap on Retry, so the retry never runs. Under heavy CPU load, 3 to 10 percent of
     * retries landed there: the retry test waited forever for a user, and the
     * unreachable-screen test passed without retrying at all.
     *
     * The app cannot hit this. There, the coroutine comes back to the main thread,
     * where publishing the outcome and finishing the Job happen in one task, so a
     * Retry tap cannot land between them. A StandardTestDispatcher on the test's
     * scheduler gives the test the same guarantee: the tail of the restore runs on
     * the test thread, and the test resumes only after it. It is not the class
     * default because it also defers the start of every launch. Other tests here
     * check the state straight after a call, which relies on the launch starting at
     * once, as Main.immediate starts it in the app.
     */
    private fun TestScope.confineToTestThread() =
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

    @Test
    fun `a stored session is restored at launch`() = runTest {
        tokens.token = "tok"
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"fresh"}"""))
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice"}"""))

        val state = viewModel().settled()

        assertEquals("alice", state.user?.username)
        assertNull(state.unreachable)
    }

    @Test
    fun `no stored session goes to the login form without a message`() = runTest {
        val state = viewModel().settled()

        assertNull(state.user)
        assertNull(state.error)
        assertNull(state.unreachable)
    }

    @Test
    fun `an unreachable server keeps the session and offers a retry`() = runTest {
        // Before, this dropped to the login form showing "Network error" - which
        // reads as "you were signed out", to someone who was not.
        tokens.token = "tok"
        val url = server.url("/").toString().trimEnd('/')
        server.close()

        val state = viewModel(url).settled()

        assertNull(state.user)
        assertNotNull(state.unreachable)
        assertNull("not a login-form error", state.error)
        assertEquals("the session is kept for the retry", "tok", tokens.token)
    }

    @Test
    fun `a server error at launch is unreachable too, not a sign-out`() = runTest {
        tokens.token = "tok"
        server.enqueue(MockResponse(code = 502, body = "Bad Gateway"))

        val state = viewModel().settled()

        assertEquals("Request failed (502)", state.unreachable)
        assertEquals("tok", tokens.token)
    }

    @Test
    fun `retry restores the session once the server answers`() = runTest {
        confineToTestThread()
        tokens.token = "tok"
        server.enqueue(MockResponse(code = 503, body = """{"detail":"Down for maintenance"}"""))
        val model = viewModel()
        assertEquals("Down for maintenance", model.settled().unreachable)
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"fresh"}"""))
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice"}"""))

        model.restore()
        val state = model.state.first { it.user != null }

        assertEquals("alice", state.user?.username)
        assertNull(state.unreachable)
    }

    @Test
    fun `a retry keeps the unreachable screen up while it runs`() = runTest {
        // Clearing it while the retry is in flight would flash the launch spinner
        // - or, if the retry failed again, bounce between the two screens.
        confineToTestThread()
        tokens.token = "tok"
        server.enqueue(MockResponse(code = 503, body = ""))
        val model = viewModel()
        model.settled()

        model.restore()
        // Confined, the retry waits for the test to yield; this runs it up to its
        // request, which the server holds until the answer below is enqueued. Timing
        // cannot finish it early, so the checks below always see it in flight.
        runCurrent()

        assertTrue("the retry is running", model.state.value.loading)
        assertNotNull(model.state.value.unreachable)
        server.enqueue(MockResponse(code = 503, body = ""))
        assertNotNull(model.settled().unreachable)
    }

    @Test
    fun `a refused session goes to login and says why`() = runTest {
        tokens.token = "ninety-days-old"
        server.enqueue(MockResponse(code = 401, body = """{"detail":"Session expired. Please sign in again."}"""))

        val state = viewModel().settled()

        assertNull(state.user)
        assertNull(state.unreachable)
        assertEquals("Your session expired. Please sign in again.", state.error)
    }

    @Test
    fun `the launch restore is what shows the full-screen spinner`() = runTest {
        tokens.token = "tok"
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"access_token":"fresh"}""")
                .headersDelay(500, TimeUnit.MILLISECONDS).build()
        )
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice"}"""))

        val model = viewModel()

        assertTrue(model.state.value.restoring)
        assertFalse(model.settled().restoring)
    }

    @Test
    fun `signing in keeps the login form up`() = runTest {
        // Signing in used to look exactly like the launch restore - loading, no
        // user, no error - so AppNavigation swapped the login form for the
        // full-screen spinner. That discarded the form, and a wrong password came
        // back to empty fields with the button's own spinner never shown.
        val model = viewModel()
        model.settled()
        server.enqueue(
            MockResponse.Builder().code(401).body("""{"detail":"Incorrect username or password"}""")
                .headersDelay(500, TimeUnit.MILLISECONDS).build()
        )

        model.signIn("alice", "wrong")

        assertTrue(model.state.value.loading)
        assertFalse(model.state.value.restoring)
        assertEquals("Incorrect username or password", model.settled().error)
    }

    @Test
    fun `the unreachable screen names the host the app talks to`() {
        assertEquals("androidmod.site", hostOf("https://androidmod.site"))
        assertEquals("10.0.0.20", hostOf("http://10.0.0.20:8000"))
        assertEquals("not a url", hostOf("not a url"))
    }

    /*
     * Email verification: what registering says, and the Plan tab's card.
     */

    private suspend fun AuthViewModel.registered(): AuthState = state.first { it.registered }

    @Test
    fun `registering says where the verification link went`() = runTest {
        val model = viewModel()
        model.settled()
        server.enqueue(MockResponse(code = 200, body = """{"message":"User registered successfully","verification_sent":true}"""))

        model.signUp("alice", "a@example.com", "secret")
        val notice = model.registered().notice.orEmpty()

        assertTrue(notice, notice.contains("a@example.com"))
        assertTrue(notice, notice.contains("verification link"))
    }

    @Test
    fun `registering without email says the link can be sent later from Plan`() = runTest {
        val model = viewModel()
        model.settled()
        server.enqueue(MockResponse(code = 200, body = """{"message":"User registered successfully","verification_sent":false}"""))

        model.signUp("alice", "a@example.com", "secret")
        val notice = model.registered().notice.orEmpty()

        assertTrue(notice, notice.contains("isn't available right now"))
        assertTrue(notice, notice.contains("Plan tab"))
        assertFalse("no link was sent, so none may be promised", notice.contains("a@example.com"))
    }

    @Test
    fun `registering against an older server promises no email`() = runTest {
        val model = viewModel()
        model.settled()
        server.enqueue(MockResponse(code = 200, body = """{"message":"User registered successfully"}"""))

        model.signUp("alice", "a@example.com", "secret")
        val notice = model.registered().notice.orEmpty()

        assertTrue(notice, notice.startsWith("Account created"))
        assertFalse(notice, notice.contains("verif"))
    }

    @Test
    fun `leaving the login form drops the registration notice`() = runTest {
        val model = viewModel()
        model.settled()
        server.enqueue(MockResponse(code = 200, body = """{"message":"ok","verification_sent":true}"""))
        model.signUp("alice", "a@example.com", "secret")
        model.registered()

        model.clearError()

        assertNull(model.state.value.notice)
    }

    /** A restored session for alice, whose email is not verified yet. */
    private suspend fun unverified(): AuthViewModel {
        tokens.token = "tok"
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"fresh"}"""))
        server.enqueue(MockResponse(code = 200, body = UNVERIFIED_ALICE))
        val model = viewModel()
        assertTrue(model.settled().user!!.needsVerification)
        repeat(2) { server.takeRequest() }
        return model
    }

    private suspend fun AuthViewModel.resent(): VerificationState =
        state.first { !it.verification.sending && it.verification.message != null }.verification

    @Test
    fun `resending shows the server's confirmation`() = runTest {
        val model = unverified()
        server.enqueue(MockResponse(code = 200, body = """{"message":"We sent a new verification link to a@example.com."}"""))

        model.resendVerification()
        val outcome = model.resent()

        assertEquals("We sent a new verification link to a@example.com.", outcome.message)
        assertFalse(outcome.failed)
        assertEquals("/api/auth/resend-verification", server.takeRequest().target)
    }

    @Test
    fun `a resend in flight is not sent twice`() = runTest {
        val model = unverified()
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"message":"sent"}""")
                .headersDelay(500, TimeUnit.MILLISECONDS).build()
        )

        model.resendVerification()
        assertTrue(model.state.value.verification.sending)
        model.resendVerification()
        model.resent()

        assertEquals(3, server.requestCount)
    }

    @Test
    fun `already verified hides the card and re-reads the user`() = runTest {
        // The link was opened in a browser, so the app still holds the old user.
        val model = unverified()
        server.enqueue(MockResponse(code = 409, body = """{"detail":"Your email address is already verified."}"""))
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice","email":"a@example.com","email_verified":true}"""))

        model.resendVerification()
        val state = model.state.first { !it.verification.sending }

        assertFalse(state.user!!.needsVerification)
        assertFalse(state.verification.failed)
        assertEquals("/api/auth/resend-verification", server.takeRequest().target)
        assertEquals("/api/users/me", server.takeRequest().target)
    }

    @Test
    fun `already verified hides the card even if re-reading the user fails`() = runTest {
        // The 409 is the server's answer about this account; a flaky users/me
        // afterwards must not leave "verify your email" next to "already verified".
        val model = unverified()
        server.enqueue(MockResponse(code = 409, body = """{"detail":"Your email address is already verified."}"""))
        server.enqueue(MockResponse(code = 502, body = "Bad Gateway"))

        model.resendVerification()

        assertFalse(model.state.first { !it.verification.sending }.user!!.needsVerification)
    }

    @Test
    fun `too many resends says to check the inbox`() = runTest {
        val model = unverified()
        server.enqueue(MockResponse(code = 429, body = """{"detail":"Too many requests, please try again later."}"""))

        model.resendVerification()
        val outcome = model.resent()

        assertTrue(outcome.failed)
        assertTrue(outcome.message!!, outcome.message!!.contains("inbox"))
        assertTrue("the card stays", model.state.value.user!!.needsVerification)
    }

    @Test
    fun `no email service shows the server's message with the support address`() = runTest {
        val model = unverified()
        val detail = "Email isn't available right now. Contact sudorm.ext@gmail.com for help."
        server.enqueue(MockResponse(code = 503, body = """{"detail":"$detail"}"""))

        model.resendVerification()
        val outcome = model.resent()

        assertTrue(outcome.failed)
        assertEquals(detail, outcome.message)
    }

    @Test
    fun `a resend with no network says so inline`() = runTest {
        val model = unverified()
        server.close()

        model.resendVerification()
        val outcome = model.resent()

        assertTrue(outcome.failed)
        assertEquals("Network error. Check your connection and try again.", outcome.message)
        assertNotNull("a network error is not a sign-out", model.state.value.user)
    }

    @Test
    fun `a resend refused as unauthenticated ends the session`() = runTest {
        val model = unverified()
        server.enqueue(MockResponse(code = 401, body = """{"detail":"Could not validate credentials"}"""))

        model.resendVerification()
        val state = model.state.first { it.user == null }

        assertEquals("Your session expired. Please sign in again.", state.error)
        assertNull(tokens.token)
    }

    @Test
    fun `coming back to Plan picks up a verification done in the browser`() = runTest {
        val model = unverified()
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice","email":"a@example.com","email_verified":true}"""))

        model.recheckVerification()

        assertFalse(model.state.first { it.user?.needsVerification == false }.user!!.needsVerification)
    }

    @Test
    fun `a failed recheck keeps the user as they were`() = runTest {
        val model = unverified()
        server.enqueue(MockResponse(code = 502, body = "Bad Gateway"))

        model.recheckVerification()!!.join()

        assertTrue(model.state.value.user!!.needsVerification)
        assertNull("a background check has nothing to report", model.state.value.error)
    }

    @Test
    fun `a recheck refused as unauthenticated ends the session`() = runTest {
        val model = unverified()
        server.enqueue(MockResponse(code = 401, body = """{"detail":"Could not validate credentials"}"""))

        model.recheckVerification()

        assertEquals("Your session expired. Please sign in again.", model.state.first { it.user == null }.error)
    }

    @Test
    fun `a verified user is never rechecked`() = runTest {
        tokens.token = "tok"
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"fresh"}"""))
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice","email_verified":true}"""))
        val model = viewModel()
        model.settled()

        assertNull("nothing to check", model.recheckVerification())
        assertEquals("only the launch restore's two requests", 2, server.requestCount)
    }

    @Test
    fun `signing out from the unreachable screen goes to the login form`() = runTest {
        tokens.token = "tok"
        server.enqueue(MockResponse(code = 503, body = ""))
        val model = viewModel()
        model.settled()

        model.signOut()

        assertNull(model.state.value.unreachable)
        assertNull(tokens.token)
    }

    private companion object {
        const val UNVERIFIED_ALICE = """{"username":"alice","email":"a@example.com","email_verified":false}"""
    }
}
