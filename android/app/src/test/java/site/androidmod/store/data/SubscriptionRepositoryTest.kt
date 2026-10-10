package site.androidmod.store.data

import kotlinx.coroutines.test.runTest
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

class SubscriptionRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var cache: FakeSubscriptionCache
    private lateinit var repository: SubscriptionRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cache = FakeSubscriptionCache()
        repository = SubscriptionRepository(
            ApiClient(OkHttpClient(), baseUrl = { server.url("/").toString().trimEnd('/') }, token = { "tok" }),
            cache,
        )
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `reads an active subscription`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """
            {"subscription":{"planId":"pro","planName":"Pro","status":"active",
             "currentPeriodEnd":"2026-10-20T00:00:00+00:00","cancelAtPeriodEnd":false}}
        """.trimIndent()))

        val subscription = repository.status()

        assertEquals("active", subscription?.status)
        assertTrue(subscription!!.isActive)
        assertFalse(subscription.cancelAtPeriodEnd)
    }

    @Test
    fun `trialing counts as active, anything else does not`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"subscription":{"status":"trialing"}}"""))
        assertTrue(repository.status()!!.isActive)

        server.enqueue(MockResponse(code = 200, body = """{"subscription":{"status":"past_due"}}"""))
        assertFalse(repository.status()!!.isActive)
    }

    @Test
    fun `no subscription is null, not an error`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"subscription":null}"""))
        assertNull(repository.status())
    }

    @Test
    fun `status caches what it read, so the next screen can start from it`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """
            {"subscription":{"planId":"pro","planName":"Pro","status":"active"}}
        """.trimIndent()))

        repository.status()

        assertEquals("Pro", repository.cached()?.subscription?.planName)
    }

    @Test
    fun `having no subscription is cached too, not left looking unknown`() = runTest {
        // Distinct from "nothing cached": the Plan card draws "Free" for the first
        // and a placeholder for the second, so the two must not collapse.
        server.enqueue(MockResponse(code = 200, body = """{"subscription":null}"""))

        repository.status()

        val cached = repository.cached()
        assertNotNull(cached)
        assertNull(cached!!.subscription)
    }

    @Test
    fun `nothing is cached before the first read`() {
        assertNull(repository.cached())
    }

    @Test
    fun `a failed read leaves the previous answer in place`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"subscription":{"status":"active"}}"""))
        repository.status()

        server.enqueue(MockResponse(code = 503, body = """{"detail":"Billing is down"}"""))
        runCatching { repository.status() }

        // An outage is not evidence the subscription ended - showing the last known
        // plan beats demoting a paying user to Free because Stripe hiccuped.
        assertEquals("active", repository.cached()?.subscription?.status)
    }

    @Test
    fun `checkout sends an empty body because the server picks the price`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"url":"https://checkout.stripe.com/c/pay/x"}"""))

        val url = repository.checkoutUrl()

        assertEquals("https://checkout.stripe.com/c/pay/x", url)
        val recorded = server.takeRequest()
        assertEquals("/api/subscription/create-checkout", recorded.target)
        assertEquals("{}", recorded.body?.utf8())
    }

    @Test
    fun `cancel and reactivate hit their endpoints`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true}"""))
        repository.cancel()
        assertEquals("/api/subscription/cancel", server.takeRequest().target)

        server.enqueue(MockResponse(code = 200, body = """{"success":true}"""))
        repository.reactivate()
        assertEquals("/api/subscription/reactivate", server.takeRequest().target)
    }

    @Test
    fun `a billing outage surfaces the server's message`() = runTest {
        server.enqueue(MockResponse(code = 503, body = """
            {"detail":"Billing is temporarily unavailable, please try again shortly"}
        """.trimIndent()))

        val error = runCatching { repository.status() }.exceptionOrNull()

        assertEquals("Billing is temporarily unavailable, please try again shortly", error?.message)
    }

    @Test
    fun `confirmSuccess surfaces the not-settled-yet 404 instead of swallowing it`() = runTest {
        // The case the Android client has to be able to retry: Stripe has taken the
        // money but the subscription is not readable back yet. There is no webhook
        // in this backend, so confirm_success is the ONLY writer of customer_id - a
        // failure that never reaches the UI is a paid account stuck on Free.
        server.enqueue(MockResponse(code = 404, body = """{"detail":"No active subscription found"}"""))

        val error = runCatching { repository.confirmSuccess() }.exceptionOrNull()

        assertTrue(error is ApiException)
        assertEquals(404, (error as ApiException).status)
        assertEquals("No active subscription found", error.message)
    }

    @Test
    fun `confirmSuccess tolerates the already-confirmed response`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"message":"Subscription already confirmed"}"""))
        repository.confirmSuccess()
        assertEquals("/api/subscription/confirm_success", server.takeRequest().target)
    }
}
