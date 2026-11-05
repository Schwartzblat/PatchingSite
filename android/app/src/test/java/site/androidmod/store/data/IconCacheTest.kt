package site.androidmod.store.data

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IconCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var cache: IconCache
    private val bytes = byteArrayOf(1, 2, 3, 4, 5)

    private fun app(pkg: String, icon: String?) =
        CatalogApp(key = "k", name = "N", packageName = pkg, icon = icon, friendsOnly = icon != null)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = File(tmp.root, "icons")
        cache = IconCache(
            dir,
            ApiClient(OkHttpClient(), baseUrl = { server.url("/").toString().trimEnd('/') }, token = { "tok" }),
        )
    }

    @After
    fun tearDown() = runCatching { server.close() }.let { }

    @Test
    fun `fetches an icon once and serves it from the cache`() = runTest {
        server.enqueue(MockResponse(code = 200, body = String(bytes, Charsets.ISO_8859_1)))
        cache.remember(listOf(app("com.tranzmate", "/api/apps/com.tranzmate/icon")))

        val first = cache.file("com.tranzmate")
        val second = cache.file("com.tranzmate")

        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/api/apps/com.tranzmate/icon", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertArrayEquals(bytes, first!!.readBytes())
        assertArrayEquals(bytes, second!!.readBytes())
    }

    @Test
    fun `returns null for a package with no icon path`() = runTest {
        cache.remember(listOf(app("com.whatsapp", null)))

        assertNull(cache.pathFor("com.whatsapp"))
        assertNull(cache.file("com.whatsapp"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `returns null when the server refuses`() = runTest {
        server.enqueue(MockResponse(code = 404))
        cache.remember(listOf(app("com.tranzmate", "/api/apps/com.tranzmate/icon")))

        assertNull(cache.file("com.tranzmate"))
        assertFalse(File(dir, "com.tranzmate.webp").exists())
        assertEquals(emptyList<String>(), dir.list()?.toList().orEmpty())
    }

    @Test
    fun `returns null when offline`() = runTest {
        cache.remember(listOf(app("com.tranzmate", "/api/apps/com.tranzmate/icon")))
        server.close()

        assertNull(cache.file("com.tranzmate"))
    }

    @Test
    fun `clear forgets paths and deletes cached files`() = runTest {
        server.enqueue(MockResponse(code = 200, body = String(bytes, Charsets.ISO_8859_1)))
        cache.remember(listOf(app("com.tranzmate", "/api/apps/com.tranzmate/icon")))
        assertTrue(cache.file("com.tranzmate")!!.exists())

        cache.clear()

        assertNull(cache.pathFor("com.tranzmate"))
        assertFalse(dir.exists())
    }
}
