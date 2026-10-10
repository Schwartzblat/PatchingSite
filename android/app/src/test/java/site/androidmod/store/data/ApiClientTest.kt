package site.androidmod.store.data

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ApiClientTest {

    private lateinit var server: MockWebServer
    private var token: String? = null

    private fun client() = ApiClient(
        client = OkHttpClient(),
        baseUrl = { server.url("/").toString().trimEnd('/') },
        token = { token },
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        token = null
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `sends the bearer token when one is held`() = runTest {
        token = "abc123"
        server.enqueue(MockResponse(code = 200, body = """{"username":"alice"}"""))

        client().get("/api/users/me")

        assertEquals("Bearer abc123", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `sends no Authorization header when signed out`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "{}"))

        client().get("/api/apps")

        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `posts JSON with the right content type`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "{}"))

        client().post("/api/login", """{"username":"alice","password":"x"}""")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals("""{"username":"alice","password":"x"}""", recorded.body?.utf8())
    }

    @Test
    fun `surfaces the server's message on failure`() = runTest {
        server.enqueue(MockResponse(code = 402, body = """{"detail":"Subscription required"}"""))

        val error = runCatching { client().get("/api/downloads") }.exceptionOrNull()

        assertTrue(error is ApiException)
        assertEquals(402, (error as ApiException).status)
        assertEquals("Subscription required", error.message)
        assertTrue(error.needsSubscription)
    }

    @Test
    fun `a dead server becomes status zero, not a raw IOException`() = runTest {
        val url = server.url("/").toString().trimEnd('/')
        server.close()
        val offline = ApiClient(OkHttpClient(), baseUrl = { url }, token = { null })

        val error = runCatching { offline.get("/api/apps") }.exceptionOrNull()

        assertTrue(error is ApiException)
        assertEquals(0, (error as ApiException).status)
        assertTrue(error.message.startsWith("Network error"))
    }

    @Test
    fun `download writes the body and reports progress`() = runTest {
        val payload = "x".repeat(4096)
        server.enqueue(MockResponse(code = 200, body = payload))
        val target = File.createTempFile("download", ".bin").also { it.deleteOnExit() }
        var lastRead = 0L

        client().download(server.url("/file").toString(), target) { read, _ -> lastRead = read }

        assertEquals(payload.length.toLong(), target.length())
        assertEquals(payload.length.toLong(), lastRead)
    }

    @Test
    fun `download leaves no partial file behind when the server fails`() = runTest {
        server.enqueue(MockResponse(code = 404, body = """{"detail":"gone"}"""))
        val target = File.createTempFile("download", ".bin").also { it.delete() }

        runCatching { client().download(server.url("/file").toString(), target) { _, _ -> } }

        assertTrue("a failed download must not leave a file", !target.exists())
    }
}
