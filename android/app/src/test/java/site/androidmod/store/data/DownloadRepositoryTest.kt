package site.androidmod.store.data

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DownloadRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: DownloadRepository
    private lateinit var target: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = DownloadRepository(
            ApiClient(OkHttpClient(), baseUrl = { server.url("/").toString().trimEnd('/') }, token = { "tok" })
        )
        target = File.createTempFile("build", ".xapk").also { it.delete(); it.deleteOnExit() }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `backoff grows and then caps at five seconds`() {
        assertEquals(1000L, DownloadRepository.pollDelayMillis(0))
        assertEquals(1500L, DownloadRepository.pollDelayMillis(1))
        assertEquals(2250L, DownloadRepository.pollDelayMillis(2))
        assertEquals(5000L, DownloadRepository.pollDelayMillis(20))
    }

    @Test
    fun `enqueues, polls until ready, then transfers the file`() = runTest {
        server.enqueue(MockResponse(code = 202, body = """{"job_id":"j1","state":"queued","cached":false}"""))
        server.enqueue(MockResponse(code = 200, body = """{"job_id":"j1","state":"running","progress":0.45,"message":"Signing"}"""))
        server.enqueue(MockResponse(code = 200, body = """
            {"job_id":"j1","state":"ready","progress":1.0,
             "download_url":"/api/downloads/j1/file?sig=abc",
             "filename":"patched.xapk","size":9}
        """.trimIndent()))
        server.enqueue(MockResponse(code = 200, body = "ninebytes"))

        val progress = mutableListOf<DownloadProgress>()
        val result = repository.download("com.tranzmate", "5.199.1.1804", target) { progress += it }

        assertEquals(9L, result.length())
        assertTrue(progress.any { it is DownloadProgress.Preparing && it.message == "Signing" })
        assertTrue(progress.any { it is DownloadProgress.Transferring })

        val created = server.takeRequest()
        assertEquals("/api/downloads", created.target)
        assertTrue(created.body!!.utf8().contains("\"package\":\"com.tranzmate\""))
    }

    @Test
    fun `a cached build skips straight to the transfer`() = runTest {
        server.enqueue(MockResponse(code = 202, body = """{"job_id":"j2","state":"ready","cached":true}"""))
        server.enqueue(MockResponse(code = 200, body = """
            {"job_id":"j2","state":"ready","progress":1.0,
             "download_url":"/api/downloads/j2/file?sig=abc","filename":"p.xapk","size":4}
        """.trimIndent()))
        server.enqueue(MockResponse(code = 200, body = "data"))

        repository.download("com.tranzmate", "5.199.1.1804", target) { }

        assertEquals(4L, target.length())
    }

    @Test
    fun `a failed job raises the server's message`() = runTest {
        server.enqueue(MockResponse(code = 202, body = """{"job_id":"j3","state":"queued"}"""))
        server.enqueue(MockResponse(code = 200, body = """
            {"job_id":"j3","state":"failed","progress":1.0,"message":"Patching failed, please try again"}
        """.trimIndent()))

        val error = runCatching { repository.download("com.tranzmate", "1.0", target) { } }.exceptionOrNull()

        assertEquals("Patching failed, please try again", error?.message)
        assertFalse(target.exists())
    }

    @Test
    fun `a job lost to a server restart is enqueued again`() = runTest {
        // The job registry is in-process, so a restart forgets it. Erroring here
        // would make a routine deploy look like a broken download.
        server.enqueue(MockResponse(code = 202, body = """{"job_id":"gone","state":"queued"}"""))
        server.enqueue(MockResponse(code = 404, body = """{"detail":"Download job not found"}"""))
        server.enqueue(MockResponse(code = 202, body = """{"job_id":"fresh","state":"queued"}"""))
        server.enqueue(MockResponse(code = 200, body = """
            {"job_id":"fresh","state":"ready","progress":1.0,
             "download_url":"/api/downloads/fresh/file?sig=abc","filename":"p.xapk","size":2}
        """.trimIndent()))
        server.enqueue(MockResponse(code = 200, body = "ok"))

        repository.download("com.tranzmate", "1.0", target) { }

        assertEquals(2L, target.length())
    }

    @Test
    fun `it gives up rather than re-enqueueing forever`() = runTest {
        server.enqueue(MockResponse(code = 202, body = """{"job_id":"a","state":"queued"}"""))
        server.enqueue(MockResponse(code = 404, body = """{"detail":"Download job not found"}"""))
        server.enqueue(MockResponse(code = 202, body = """{"job_id":"b","state":"queued"}"""))
        server.enqueue(MockResponse(code = 404, body = """{"detail":"Download job not found"}"""))

        val error = runCatching { repository.download("com.tranzmate", "1.0", target) { } }.exceptionOrNull()

        assertTrue(error is ApiException)
    }

    @Test
    fun `a server error while polling does not fail the job`() = runTest {
        // One 502 from nginx while uvicorn restarts used to fail a download that
        // was still being prepared, with a build nearly done on the server.
        server.enqueue(MockResponse(code = 202, body = """{"job_id":"j","state":"queued"}"""))
        server.enqueue(MockResponse(code = 502, body = "Bad Gateway"))
        server.enqueue(MockResponse(code = 503, body = """{"detail":"busy"}"""))
        server.enqueue(ready("j", size = 2))
        server.enqueue(MockResponse(code = 200, body = "ok"))

        repository.download("com.tranzmate", "1.0", target) { }

        assertEquals(2L, target.length())
    }

    @Test
    fun `a dropped connection while polling does not fail the job`() = runTest {
        // OkHttp's own retry is off so the drop reaches the repository, which is
        // what a real network change mid-poll does once OkHttp has given up.
        repository = DownloadRepository(
            ApiClient(
                OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                baseUrl = { server.url("/").toString().trimEnd('/') },
                token = { "tok" },
            )
        )
        server.enqueue(MockResponse(code = 202, body = """{"job_id":"j","state":"queued"}"""))
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        server.enqueue(ready("j", size = 2))
        server.enqueue(MockResponse(code = 200, body = "ok"))

        repository.download("com.tranzmate", "1.0", target) { }

        assertEquals(2L, target.length())
    }

    @Test
    fun `polling gives up after repeated server errors`() = runTest {
        server.enqueue(MockResponse(code = 202, body = """{"job_id":"j","state":"queued"}"""))
        repeat(DownloadRepository.POLL_RETRIES + 1) {
            server.enqueue(MockResponse(code = 503, body = """{"detail":"down for maintenance"}"""))
        }

        val error = runCatching { repository.download("com.tranzmate", "1.0", target) { } }.exceptionOrNull()

        assertEquals(503, (error as ApiException).status)
        assertEquals("enqueue, then one poll plus every retry", DownloadRepository.POLL_RETRIES + 2, server.requestCount)
    }

    @Test
    fun `a client error while polling is not retried`() = runTest {
        // A 4xx is an answer, not a blip: asking again gets the same answer.
        server.enqueue(MockResponse(code = 202, body = """{"job_id":"j","state":"queued"}"""))
        server.enqueue(MockResponse(code = 403, body = """{"detail":"Not your download"}"""))

        val error = runCatching { repository.download("com.tranzmate", "1.0", target) { } }.exceptionOrNull()

        assertEquals(403, (error as ApiException).status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the retry budget is per run of failures, not per download`() = runTest {
        // A long preparation on a flaky network can see many blips in total; only
        // an unbroken run of them means the server is really gone.
        server.enqueue(MockResponse(code = 202, body = """{"job_id":"j","state":"queued"}"""))
        repeat(3) {
            repeat(DownloadRepository.POLL_RETRIES) { server.enqueue(MockResponse(code = 503, body = "")) }
            server.enqueue(MockResponse(code = 200, body = """{"job_id":"j","state":"running","progress":0.5}"""))
        }
        server.enqueue(ready("j", size = 2))
        server.enqueue(MockResponse(code = 200, body = "ok"))

        repository.download("com.tranzmate", "1.0", target) { }

        assertEquals(2L, target.length())
    }

    @Test
    fun `a transfer that outlives its signed link resumes on a fresh one`() = runTest {
        repository = DownloadRepository(
            ApiClient(
                OkHttpClient(),
                baseUrl = { server.url("/").toString().trimEnd('/') },
                token = { "tok" },
                resumeDelayMillis = { 0L },
            )
        )
        server.enqueue(MockResponse(code = 202, body = """{"job_id":"j","state":"ready","cached":true}"""))
        server.enqueue(ready("j", size = 8))
        server.enqueue(
            MockResponse.Builder().code(200).body("abcd").setHeader("Content-Length", 8)
                .setHeader("ETag", "\"v1\"").onResponseEnd(SocketEffect.ShutdownConnection).build()
        )
        // The resume, past the link's ten minutes.
        server.enqueue(MockResponse(code = 404, body = """{"detail":"Download job not found"}"""))
        server.enqueue(MockResponse(code = 200, body = """
            {"job_id":"j","state":"ready","progress":1.0,
             "download_url":"/api/downloads/j/file?sig=fresh","filename":"p.xapk","size":8}
        """.trimIndent()))
        server.enqueue(
            MockResponse.Builder().code(206).body("efgh").setHeader("Content-Range", "bytes 4-7/8").build()
        )

        repository.download("com.tranzmate", "1.0", target) { }

        assertEquals("abcdefgh", target.readText())
    }

    private fun ready(jobId: String, size: Int) = MockResponse(code = 200, body = """
        {"job_id":"$jobId","state":"ready","progress":1.0,
         "download_url":"/api/downloads/$jobId/file?sig=abc","filename":"p.xapk","size":$size}
    """.trimIndent())

    @Test
    fun `a 402 from enqueue propagates so the UI can offer a subscription`() = runTest {
        server.enqueue(MockResponse(code = 402, body = """{"detail":"Subscription required"}"""))

        val error = runCatching { repository.download("com.whatsapp", "1.0", target) { } }.exceptionOrNull()

        assertTrue((error as ApiException).needsSubscription)
    }
}
