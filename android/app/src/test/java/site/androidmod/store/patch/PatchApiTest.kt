package site.androidmod.store.patch

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import site.androidmod.store.data.ApiClient
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.needsSubscription

class PatchApiTest {

    private lateinit var server: MockWebServer
    private lateinit var patch: PatchApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        patch = PatchApi(
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
    fun `latest parses the record`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"package":"com.whatsapp","version_code":250100,"version_name":"2.25.1.0",
                  "apk_url":"https://example.com/w.apk","apk_sha256":"aa","dex_sha256":"bb",
                  "patcher_version":"p7","revision":3,"features_off":["x"],"extra":1}""",
            ),
        )

        val latest = patch.latest("com.whatsapp")

        assertEquals("com.whatsapp", latest.packageName)
        assertEquals(250100L, latest.versionCode)
        assertEquals("2.25.1.0", latest.versionName)
        assertEquals("https://example.com/w.apk", latest.apkUrl)
        assertEquals("aa", latest.apkSha256)
        assertEquals("bb", latest.dexSha256)
        assertEquals("p7", latest.patcherVersion)
        assertEquals(3, latest.revision)
        assertEquals(listOf("x"), latest.featuresOff)
        val request = server.takeRequest()
        assertEquals("/api/patch/com.whatsapp/latest", request.url.encodedPath)
        assertEquals("42", request.headers["X-Store-Version-Code"])
    }

    @Test
    fun `426 raises StoreUpdateRequired with the min code`() = runTest {
        server.enqueue(MockResponse(code = 426, body = """{"detail":"Store update required","min_store_version_code":77}"""))
        try {
            patch.latest("com.whatsapp")
            fail("expected StoreUpdateRequired")
        } catch (error: StoreUpdateRequired) {
            assertEquals(77L, error.minStoreVersionCode)
        }
    }

    @Test
    fun `402 stays an ApiException that needs a subscription`() = runTest {
        server.enqueue(MockResponse(code = 402, body = """{"detail":"Subscription required"}"""))
        try {
            patch.latest("com.whatsapp")
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertTrue(error.needsSubscription)
        }
    }

    @Test
    fun `record 404 is null`() = runTest {
        server.enqueue(MockResponse(code = 404, body = """{"detail":"Unknown build"}"""))
        assertNull(patch.record("com.whatsapp", "p7", apkSha256 = "aa"))
    }

    @Test
    fun `record sends exactly one hash and the patcher version`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"k":1}"""))
        server.enqueue(MockResponse(code = 200, body = """{"k":2}"""))

        assertEquals("""{"k":1}""", patch.record("com.whatsapp", "p 7", apkSha256 = "aa"))
        assertEquals("""{"k":2}""", patch.record("com.whatsapp", "p7", dexSha256 = "bb"))

        val apk = server.takeRequest().url
        assertEquals("/api/patch/com.whatsapp/artifacts", apk.encodedPath)
        assertEquals("p 7", apk.queryParameter("patcher_version"))
        assertEquals("aa", apk.queryParameter("apk_sha256"))
        assertNull(apk.queryParameter("dex_sha256"))
        val dex = server.takeRequest().url
        assertEquals("bb", dex.queryParameter("dex_sha256"))
        assertNull(dex.queryParameter("apk_sha256"))
    }

    @Test
    fun `record needs exactly one hash`() = runTest {
        for (args in listOf(null to null, "a" to "b")) {
            try {
                patch.record("com.whatsapp", "p7", args.first, args.second)
                fail("expected IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                // ok
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `bundle asks conditionally and reports not modified`() = runTest {
        val target = java.io.File.createTempFile("bundle", ".zip").also { it.deleteOnExit() }
        target.writeText("old")
        server.enqueue(MockResponse(code = 304))

        assertNull(patch.bundle("com.whatsapp", "p7", target, cachedSha256 = "abc"))

        val request = server.takeRequest()
        assertEquals("/api/patch/com.whatsapp/bundle/p7", request.url.encodedPath)
        assertEquals("\"abc\"", request.headers["If-None-Match"])
        assertEquals("old", target.readText())
    }

    @Test
    fun `appToken posts the package`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"token":"t0k"}"""))

        assertEquals("t0k", patch.appToken("com.whatsapp"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/app-token", request.url.encodedPath)
        assertEquals("""{"package":"com.whatsapp"}""", request.body?.utf8())
    }

    @Test
    fun `signingKey parses all fields`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"pkcs12":"QUJD","password":"pw","alias":"key0","cert_sha256":"s256","cert_sha1":"s1"}""",
            ),
        )

        val key = patch.signingKey()

        assertEquals("QUJD", key.pkcs12)
        assertEquals("pw", key.password)
        assertEquals("key0", key.alias)
        assertEquals("s256", key.certSha256)
        assertEquals("s1", key.certSha1)
        assertFalse(key.toString().contains("pw"))
        assertEquals("/api/signing-key", server.takeRequest().url.encodedPath)
    }
}
