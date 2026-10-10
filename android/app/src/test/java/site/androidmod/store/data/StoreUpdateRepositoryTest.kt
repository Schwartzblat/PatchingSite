package site.androidmod.store.data

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

class StoreUpdateRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: StoreUpdateRepository
    private lateinit var target: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = StoreUpdateRepository(
            ApiClient(OkHttpClient(), baseUrl = { server.url("/").toString().trimEnd('/') }, token = { null })
        )
        target = File.createTempFile("store-update", ".apk").also { it.delete(); it.deleteOnExit() }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `reads the published build's version`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """
            {"available":true,"filename":"patched-store.apk","size":28025397,
             "updated_at":"2026-09-30 11:13","version_code":389000,"version_name":"1.0"}
        """.trimIndent()))

        val info = repository.info()

        assertEquals(StoreInfo(available = true, size = 28025397, versionCode = 389000, versionName = "1.0"), info)
        assertEquals("/api/store", server.takeRequest().target)
    }

    @Test
    fun `a build the server could not read has no version`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """
            {"available":true,"filename":"patched-store.apk","size":12,
             "updated_at":"2026-09-30 11:13","version_code":null,"version_name":null}
        """.trimIndent()))

        assertNull(repository.info().versionCode)
    }

    @Test
    fun `an older server that sends no version fields still parses`() = runTest {
        // The live server predates version_code. Failing to deserialise would turn
        // "cannot tell" into an error message on every launch.
        server.enqueue(MockResponse(code = 200, body = """
            {"available":true,"filename":"patched-store.apk","size":12,"updated_at":"2026-09-30 11:13"}
        """.trimIndent()))

        assertNull(repository.info().versionCode)
    }

    @Test
    fun `nothing published is reported as unavailable`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"available":false}"""))

        assertFalse(repository.info().available)
    }

    @Test
    fun `downloads the published build with progress`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "apk-bytes"))

        val progress = mutableListOf<Long>()
        val file = repository.download(target) { read, _ -> progress += read }

        assertEquals("apk-bytes", file.readText())
        assertEquals(9L, progress.last())
        assertEquals("/api/store/download", server.takeRequest().target)
    }
}
