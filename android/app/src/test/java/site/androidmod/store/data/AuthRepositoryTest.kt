package site.androidmod.store.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var tokens: FakeTokenStore
    private lateinit var subscriptions: FakeSubscriptionCache
    private lateinit var repository: AuthRepository

    /** TokenStore needs a Context; the repository only needs somewhere to put a string. */
    private class FakeTokenStore : TokenHolder {
        override var token: String? = null
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokens = FakeTokenStore()
        subscriptions = FakeSubscriptionCache()
        repository = AuthRepository(
            api = ApiClient(
                client = OkHttpClient(),
                baseUrl = { server.url("/").toString().trimEnd('/') },
                token = { tokens.token },
            ),
            tokens = tokens,
            subscriptions = subscriptions,
        )
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `login stores the token and returns the user`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"tok","expires_in":2592000}"""))
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice","email":"a@example.com"}"""))

        val user = repository.login("alice", "secret")

        assertEquals("alice", user.username)
        assertEquals("tok", tokens.token)
        assertTrue(repository.isSignedIn)
        assertEquals("/api/auth/token", server.takeRequest().target)
        assertEquals("/api/users/me", server.takeRequest().target)
    }

    @Test
    fun `a rejected login leaves no token behind`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"detail":"Incorrect username or password"}"""))

        val error = runCatching { repository.login("alice", "wrong") }.exceptionOrNull()

        assertEquals("Incorrect username or password", error?.message)
        assertNull(tokens.token)
        assertFalse(repository.isSignedIn)
    }

    @Test
    fun `a token that cannot fetch its own user is discarded`() = runTest {
        // Drives login()'s cleanup branch, which the test above cannot reach: there
        // the token endpoint itself fails, so nothing is ever stored and the catch
        // block could be deleted without failing anything. Here the token endpoint
        // SUCCEEDS - a token is stored - and then /users/me fails. Keeping that
        // token would present a signed-in UI that fails on every screen.
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"tok","expires_in":2592000}"""))
        server.enqueue(MockResponse(code = 500, body = """{"detail":"boom"}"""))

        val error = runCatching { repository.login("alice", "secret") }.exceptionOrNull()

        assertTrue(error is ApiException)
        assertNull("a token whose user cannot be fetched must not be kept", tokens.token)
        assertFalse(repository.isSignedIn)
    }

    @Test
    fun `restoreSession renews the token before using it`() = runTest {
        tokens.token = "old"
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"fresh","expires_in":2592000}"""))
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice"}"""))

        val restored = repository.restoreSession()

        assertEquals("alice", (restored as SessionRestore.Restored).user.username)
        assertEquals("fresh", tokens.token)
    }

    @Test
    fun `restoreSession clears an expired token and reports it expired`() = runTest {
        tokens.token = "expired"
        server.enqueue(MockResponse(code = 401, body = """{"detail":"Authentication required!"}"""))

        assertEquals(SessionRestore.Expired, repository.restoreSession())
        assertNull("an expired token must not be kept", tokens.token)
    }

    @Test
    fun `hitting the renewal cap reports the session expired`() = runTest {
        // The server refuses to renew a chain older than 90 days with a 401. That
        // used to return the same null as "never signed in", so the user landed on
        // the login form with no idea why they had been signed out.
        tokens.token = "ninety-days-old"
        server.enqueue(MockResponse(code = 401, body = """{"detail":"Session expired. Please sign in again."}"""))

        assertEquals(SessionRestore.Expired, repository.restoreSession())
        assertNull(tokens.token)
    }

    @Test
    fun `a token refused by users-me after renewing is expired too`() = runTest {
        tokens.token = "old"
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"fresh","expires_in":2592000}"""))
        server.enqueue(MockResponse(code = 401, body = """{"detail":"Could not validate credentials"}"""))

        assertEquals(SessionRestore.Expired, repository.restoreSession())
        assertNull("a token the server refuses must not survive to the next launch", tokens.token)
    }

    @Test
    fun `a server error during restore keeps the token`() = runTest {
        tokens.token = "good"
        server.enqueue(MockResponse(code = 502, body = "Bad Gateway"))

        val error = runCatching { repository.restoreSession() }.exceptionOrNull()

        assertEquals(502, (error as ApiException).status)
        assertEquals("a server that is down is not an expired session", "good", tokens.token)
    }

    @Test
    fun `restoreSession keeps the token when the server is merely unreachable`() = runTest {
        tokens.token = "good"
        server.close()

        val error = runCatching { repository.restoreSession() }.exceptionOrNull()

        assertTrue(error is ApiException)
        assertEquals("a network blip is not an expired session", "good", tokens.token)
    }

    @Test
    fun `restoreSession reports signed out when there was never a token`() = runTest {
        assertEquals(SessionRestore.SignedOut, repository.restoreSession())
        assertEquals("nothing to restore, so nothing is asked", 0, server.requestCount)
    }

    @Test
    fun `register posts the account details`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"message":"User registered successfully","verification_sent":true}"""))

        repository.register("alice", "a@example.com", "secret")

        val recorded = server.takeRequest()
        assertEquals("/api/register", recorded.target)
        assertTrue(recorded.body!!.utf8().contains("\"email\":\"a@example.com\""))
    }

    @Test
    fun `changePassword posts both passwords with the session's token`() = runTest {
        tokens.token = "tok"
        server.enqueue(MockResponse(code = 200, body = """{"message":"Your password has been changed."}"""))

        val message = repository.changePassword("old secret", "new secret")

        assertEquals("Your password has been changed.", message)
        val recorded = server.takeRequest()
        assertEquals("/api/users/me/password", recorded.target)
        assertEquals("POST", recorded.method)
        assertEquals("Bearer tok", recorded.headers["Authorization"])
        val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("old secret", body["current_password"]?.jsonPrimitive?.content)
        assertEquals("new secret", body["new_password"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a refused password change keeps the session`() = runTest {
        // The server answers a wrong current password with 400, never 401, so the
        // app has no reason to treat it as a dead session.
        tokens.token = "tok"
        server.enqueue(MockResponse(code = 400, body = """{"detail":"Your current password is incorrect."}"""))

        val error = runCatching { repository.changePassword("wrong", "new secret") }.exceptionOrNull()

        assertEquals(400, (error as ApiException).status)
        assertEquals("Your current password is incorrect.", error.message)
        assertEquals("tok", tokens.token)
    }

    /*
     * Email verification. Unverified accounts may sign in and browse, but the
     * server refuses them a checkout until they open the link it emailed.
     */

    @Test
    fun `a user the server says is unverified is unverified`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice","email":"a@example.com","email_verified":false}"""))

        assertTrue(repository.me().needsVerification)
    }

    @Test
    fun `a verified user needs nothing`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice","email_verified":true}"""))

        assertFalse(repository.me().needsVerification)
    }

    @Test
    fun `a server that predates verification makes nobody look unverified`() = runTest {
        // Reading a missing field as "unverified" would put a card telling every
        // user to verify, with a Resend button that 404s, in front of every account.
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice","email":"a@example.com"}"""))

        assertFalse(repository.me().needsVerification)
    }

    @Test
    fun `a null verification flag is not read as unverified either`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice","email_verified":null}"""))

        assertFalse(repository.me().needsVerification)
    }

    @Test
    fun `register reports that the verification email went out`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"message":"User registered successfully","verification_sent":true}"""))

        assertEquals(true, repository.register("alice", "a@example.com", "secret"))
    }

    @Test
    fun `register reports that no verification email could be sent`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"message":"User registered successfully","verification_sent":false}"""))

        assertEquals(false, repository.register("alice", "a@example.com", "secret"))
    }

    @Test
    fun `register against an older server says nothing about verification`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"message":"User registered successfully"}"""))

        assertNull(repository.register("alice", "a@example.com", "secret"))
    }

    @Test
    fun `an unreadable register body still counts as registered`() = runTest {
        // The account exists by the time the body is read. Failing here would tell
        // the user to try again, and the retry would answer "username taken".
        server.enqueue(MockResponse(code = 200, body = "OK"))

        assertNull(repository.register("alice", "a@example.com", "secret"))
    }

    @Test
    fun `resendVerification posts with the session and returns the server's message`() = runTest {
        tokens.token = "tok"
        server.enqueue(MockResponse(code = 200, body = """{"message":"We sent a new verification link to a@example.com."}"""))

        val message = repository.resendVerification()

        assertEquals("We sent a new verification link to a@example.com.", message)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/auth/resend-verification", recorded.target)
        assertEquals("Bearer tok", recorded.headers["Authorization"])
    }

    @Test
    fun `resendVerification passes the server's refusal through`() = runTest {
        tokens.token = "tok"
        server.enqueue(MockResponse(code = 409, body = """{"detail":"Your email address is already verified."}"""))

        val error = runCatching { repository.resendVerification() }.exceptionOrNull() as ApiException

        assertEquals(409, error.status)
        assertEquals("Your email address is already verified.", error.message)
    }

    @Test
    fun `expireSession drops the token and raises the signal`() = runTest {
        // The screens that see a mid-session 401 are not the screen that can do
        // anything about it, so they raise this and AuthViewModel routes to login.
        tokens.token = "expired"

        repository.expireSession()

        assertNull("a rejected token must not survive to the next launch", tokens.token)
        assertTrue(repository.sessionExpired.value)
    }

    @Test
    fun `an acknowledged expiry does not fire again`() = runTest {
        repository.expireSession()
        repository.acknowledgeExpiry()

        assertFalse(repository.sessionExpired.value)
    }

    @Test
    fun `signing back in clears a pending expiry`() = runTest {
        repository.expireSession()
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"tok","expires_in":2592000}"""))
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice"}"""))

        repository.login("alice", "secret")

        assertFalse("a fresh sign-in must not be bounced straight back out", repository.sessionExpired.value)
    }

    @Test
    fun `logout forgets the token`() = runTest {
        tokens.token = "tok"
        repository.logout()
        assertNull(tokens.token)
        assertFalse(repository.isSignedIn)
    }

    /*
     * The Plan tab paints its first frame from the cached subscription, before the
     * network answers. Anything left in that cache when a session ends is shown to
     * whoever signs in next - so the three ways out of a session all have to empty
     * it, and so does signing in as somebody else.
     */

    @Test
    fun `logout empties the cached subscription`() = runTest {
        subscriptions.write(SubscriptionEnvelope(Subscription(planName = "Pro", status = "active")))

        repository.logout()

        assertNull("the next account would have been shown this plan", subscriptions.read())
    }

    @Test
    fun `an expired session empties the cached subscription`() = runTest {
        subscriptions.write(SubscriptionEnvelope(Subscription(planName = "Pro", status = "active")))

        repository.expireSession()

        assertNull(subscriptions.read())
    }

    @Test
    fun `a 401 on restore empties the cached subscription`() = runTest {
        subscriptions.write(SubscriptionEnvelope(Subscription(planName = "Pro", status = "active")))
        tokens.token = "stale"
        server.enqueue(MockResponse(code = 401, body = """{"detail":"Could not validate credentials"}"""))

        assertEquals(SessionRestore.Expired, repository.restoreSession())

        assertNull(subscriptions.read())
    }

    @Test
    fun `signing in empties the previous account's cached subscription`() = runTest {
        subscriptions.write(SubscriptionEnvelope(Subscription(planName = "Pro", status = "active")))
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"tok","expires_in":2592000}"""))
        server.enqueue(MockResponse(code = 200, body = """{"username":"bob"}"""))

        repository.login("bob", "secret")

        assertNull("bob must not be shown alice's plan", subscriptions.read())
    }
}
