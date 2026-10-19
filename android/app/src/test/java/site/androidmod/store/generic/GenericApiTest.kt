package site.androidmod.store.generic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import site.androidmod.store.data.ApiClient
import site.androidmod.store.data.ApiException
import site.androidmod.store.patch.StoreUpdateRequired
import java.time.Instant

class GenericApiTest {

    private lateinit var server: MockWebServer
    private lateinit var generic: GenericApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        generic = GenericApi(
            ApiClient(
                client = OkHttpClient(),
                baseUrl = { server.url("/").toString().trimEnd('/') },
                token = { "tok" },
                resumeDelayMillis = { 0L },
                storeVersionCode = { 42L },
            ),
        )
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `status parses entries in order`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"enabled":true,"works_with":[
                  {"package":"b.app","name":"B","note":"nb","tested_version":"2","tested_on":"2026-10-01","x":1},
                  {"package":"a.app","name":"A","note":"na","tested_version":"1","tested_on":"2026-09-01"}]}""",
            ),
        )

        val status = generic.status()

        assertTrue(status.enabled)
        assertEquals(listOf("b.app", "a.app"), status.worksWith.map { it.packageName })
        assertEquals(WorksWithEntry("b.app", "B", "nb", "2", "2026-10-01"), status.worksWith[0])
        assertEquals("/api/generic", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `latest parses and sends store version code`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"package":"generic","patcher_version":"g3","bundle_id":"bid","sha256":"ab",
                  "size":1234,"min_sdk":33,"min_store_version_code":40,"revision":2}""",
            ),
        )

        val latest = generic.latest()

        assertEquals(GenericLatest("generic", "g3", "bid", "ab", 1234L, 33, 40L, 2), latest)
        val request = server.takeRequest()
        assertEquals("/api/patch/generic/latest", request.url.encodedPath)
        assertEquals("42", request.headers["X-Store-Version-Code"])
    }

    @Test
    fun `latest 426 gives store update required`() = runTest {
        server.enqueue(MockResponse(code = 426, body = """{"detail":"Store update required","min_store_version_code":77}"""))
        try {
            generic.latest()
            fail("expected StoreUpdateRequired")
        } catch (error: StoreUpdateRequired) {
            assertEquals(77L, error.minStoreVersionCode)
        }
    }

    @Test
    fun `latest 426 with an unreadable body gives zero`() = runTest {
        server.enqueue(MockResponse(code = 426, body = "[1]"))
        try {
            generic.latest()
            fail("expected StoreUpdateRequired")
        } catch (error: StoreUpdateRequired) {
            assertEquals(0L, error.minStoreVersionCode)
        }
    }

    @Test
    fun `latest other failures stay ApiException`() = runTest {
        server.enqueue(MockResponse(code = 402, body = """{"detail":"Subscription required"}"""))
        try {
            generic.latest()
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(402, error.status)
        }
    }

    @Test
    fun `app token posts generic and parses expiry`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"token":"secret-token","expires_at":"2027-11-23T10:00:00Z"}"""))

        val token = generic.appToken()

        assertEquals("secret-token", token.token)
        assertEquals(Instant.parse("2027-11-23T10:00:00Z").toEpochMilli(), token.expiresAtMillis)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/app-token", request.url.encodedPath)
        assertEquals("""{"package":"generic"}""", request.body?.utf8())
    }

    @Test
    fun `app token without expiry fails`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"token":"t"}"""))
        try {
            generic.appToken()
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(0, error.status)
        }
    }

    @Test
    fun `app token with a non ISO expiry fails`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"token":"t","expires_at":"tomorrow"}"""))
        try {
            generic.appToken()
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(0, error.status)
        }
    }

    @Test
    fun `report posts exact body`() = runTest {
        server.enqueue(MockResponse(code = 201, body = """{"message":"Thanks! Your report was sent."}"""))

        val message = generic.report(
            GenericReportRequest("com.x", "X App", "1.2", 12L, worked = true, note = "fine", storeVersionCode = 42L, androidSdk = 35),
        )

        assertEquals("Thanks! Your report was sent.", message)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/generic/reports", request.url.encodedPath)
        assertEquals(
            """{"package":"com.x","app_name":"X App","version_name":"1.2","version_code":12,"worked":true,"note":"fine","store_version_code":42,"android_sdk":35}""",
            request.body?.utf8(),
        )
    }

    @Test
    fun `report leaves note out when null`() = runTest {
        server.enqueue(MockResponse(code = 201, body = """{"message":"ok"}"""))

        generic.report(GenericReportRequest("com.x", "X", "1", 1L, worked = false, storeVersionCode = 42L, androidSdk = 34))

        val body = server.takeRequest().body?.utf8().orEmpty()
        assertFalse(body.contains("note"))
        assertEquals(
            """{"package":"com.x","app_name":"X","version_name":"1","version_code":1,"worked":false,"store_version_code":42,"android_sdk":34}""",
            body,
        )
    }

    @Test
    fun `token to string hides token`() {
        val text = GenericToken("secret-token", 5L).toString()
        assertFalse(text.contains("secret-token"))
    }
}
