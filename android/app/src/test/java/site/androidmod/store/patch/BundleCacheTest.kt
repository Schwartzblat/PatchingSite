package site.androidmod.store.patch

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import site.androidmod.patch.PatchException
import site.androidmod.store.data.ApiClient
import java.io.File

class BundleCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cache: BundleCache
    private val keys = newTestKeyPair()
    private val dir get() = File(folder.root, "bundles")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = PatchApi(
            ApiClient(
                client = OkHttpClient(),
                baseUrl = { server.url("/").toString().trimEnd('/') },
                token = { "tok" },
                resumeDelayMillis = { 0L },
            ),
        )
        cache = BundleCache(dir, api, keys.public)
    }

    @After
    fun tearDown() = server.close()

    private fun bundleBytes(pkg: String = "com.whatsapp", version: String = "3.0.0"): ByteArray =
        testBundleZip(folder.newFile(), pkg, version, keyPair = keys).readBytes()

    private fun enqueue(bytes: ByteArray, etag: String = "etag1") = server.enqueue(
        MockResponse.Builder().code(200).addHeader("ETag", "\"$etag\"").body(Buffer().write(bytes)).build(),
    )

    @Test
    fun `first open downloads`() = runTest {
        enqueue(bundleBytes())

        val bundle = cache.open("com.whatsapp", "3.0.0")

        assertEquals("3.0.0", bundle.meta.patcherVersion)
        assertNull(server.takeRequest().headers["If-None-Match"])
        assertEquals("etag1", File(dir, "com.whatsapp/3.0.0.sha256").readText())
        assertTrue(File(dir, "com.whatsapp/3.0.0.zip").isFile)
    }

    @Test
    fun `second open sends If-None-Match and uses the cache on 304`() = runTest {
        enqueue(bundleBytes())
        cache.open("com.whatsapp", "3.0.0")
        server.takeRequest()
        server.enqueue(MockResponse.Builder().code(304).build())

        val bundle = cache.open("com.whatsapp", "3.0.0")

        assertEquals("com.whatsapp", bundle.meta.packageName)
        assertEquals("\"etag1\"", server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun `tampered cache is deleted and BadBundle raised`() = runTest {
        enqueue(bundleBytes())
        cache.open("com.whatsapp", "3.0.0")
        val zip = File(dir, "com.whatsapp/3.0.0.zip")
        zip.writeBytes(zip.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        server.enqueue(MockResponse.Builder().code(304).build())

        try {
            cache.open("com.whatsapp", "3.0.0")
            fail("expected BadBundle")
        } catch (expected: PatchException.BadBundle) {
        }
        assertFalse(zip.exists())
        assertFalse(File(dir, "com.whatsapp/3.0.0.sha256").exists())
    }

    @Test
    fun `bundle for another patcher version is refused`() = runTest {
        enqueue(bundleBytes(version = "2.9.9"))

        try {
            cache.open("com.whatsapp", "3.0.0")
            fail("expected BadBundle")
        } catch (expected: PatchException.BadBundle) {
        }
        assertFalse(File(dir, "com.whatsapp/3.0.0.zip").exists())
    }

    @Test
    fun `names that could leave the cache directory are refused`() = runTest {
        try {
            cache.open("../evil", "3.0.0")
            fail("expected BadBundle")
        } catch (expected: PatchException.BadBundle) {
        }
        assertEquals(0, server.requestCount)
    }
}
