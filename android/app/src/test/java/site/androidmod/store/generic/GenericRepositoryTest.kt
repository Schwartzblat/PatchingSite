package site.androidmod.store.generic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import site.androidmod.store.data.ApiClient
import site.androidmod.store.data.ApiException

class GenericRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GenericApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = GenericApi(
            ApiClient(
                client = OkHttpClient(),
                baseUrl = { server.url("/").toString().trimEnd('/') },
                token = { "tok" },
                resumeDelayMillis = { 0L },
            ),
        )
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `status is null before the first read`() {
        assertNull(GenericRepository(api).status.value)
    }

    @Test
    fun `status starts at the seed`() {
        val seed = GenericStatus(enabled = true)
        assertEquals(seed, GenericRepository(api, seed).status.value)
    }

    @Test
    fun `repository keeps last status on failure`() = runTest {
        val repository = GenericRepository(api)
        server.enqueue(MockResponse(code = 200, body = """{"enabled":true,"works_with":[]}"""))
        server.enqueue(MockResponse(code = 500, body = """{"detail":"boom"}"""))
        server.enqueue(MockResponse(code = 500, body = """{"detail":"boom"}"""))
        server.enqueue(MockResponse(code = 500, body = """{"detail":"boom"}"""))

        val first = repository.refreshStatus()
        assertEquals(GenericStatus(enabled = true), first)
        assertEquals(first, repository.status.value)

        try {
            repository.refreshStatus()
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(500, error.status)
        }
        assertEquals(first, repository.status.value)
    }
}
