package site.androidmod.store.data

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * ApiClient.download against a server that honours Range, as the contract requires
 * of /api/downloads/{id}/file and /api/store/download: 206 + Content-Range.
 */
class ApiClientDownloadTest {

    private lateinit var server: MockWebServer
    private lateinit var target: File
    private var freeBytes = Long.MAX_VALUE

    private fun client() = ApiClient(
        client = OkHttpClient(),
        baseUrl = { server.url("/").toString().trimEnd('/') },
        token = { "tok" },
        reserveSpace = { _, bytes -> (bytes - freeBytes).coerceAtLeast(0) },
        resumeDelayMillis = { 0L },
    )

    private suspend fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        client().download("/file", target, onProgress = onProgress)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        target = File.createTempFile("download", ".bin").also { it.delete(); it.deleteOnExit() }
    }

    @After
    fun tearDown() = server.close()

    /**
     * Promises [total] bytes, sends only [sent], then drops the connection - what a
     * transfer looks like when the network goes away halfway through.
     */
    private fun cutOff(sent: String, total: Int, etag: String? = "\"v1\"") = MockResponse.Builder()
        .code(200)
        .body(sent)
        .setHeader("Content-Length", total)
        .apply { if (etag != null) setHeader("ETag", etag) }
        .onResponseEnd(SocketEffect.ShutdownConnection)
        .build()

    private fun partial(body: String, start: Long, total: Long) = MockResponse.Builder()
        .code(206)
        .body(body)
        .setHeader("Content-Range", "bytes $start-${start + body.length - 1}/$total")
        .setHeader("ETag", "\"v1\"")
        .build()

    @Test
    fun `an interrupted transfer resumes where it stopped`() = runTest {
        server.enqueue(cutOff("abcd", total = 8))
        server.enqueue(partial("efgh", start = 4, total = 8))
        var last = 0L to 0L

        download { read, total -> last = read to total }

        assertEquals("abcdefgh", target.readText())
        assertEquals(8L to 8L, last)
        val first = server.takeRequest()
        assertNull("the first request asks for everything", first.headers["Range"])
        val resumed = server.takeRequest()
        assertEquals("bytes=4-", resumed.headers["Range"])
        assertEquals("the build must not change underneath a resume", "\"v1\"", resumed.headers["If-Range"])
        assertEquals("Bearer tok", resumed.headers["Authorization"])
    }

    @Test
    fun `a server that answers a range with the whole file starts over`() = runTest {
        // 200 to a Range request: the server ignored it, or If-Range no longer
        // matched because the build changed. Appending would stitch two files.
        server.enqueue(cutOff("abcd", total = 8))
        server.enqueue(MockResponse(code = 200, body = "ABCDEFGH"))

        download()

        assertEquals("ABCDEFGH", target.readText())
    }

    @Test
    fun `a range that does not start where the file ends starts over`() = runTest {
        server.enqueue(cutOff("abcd", total = 8))
        server.enqueue(partial("cdefgh", start = 2, total = 8))
        server.enqueue(MockResponse(code = 200, body = "abcdefgh"))

        download()

        assertEquals("abcdefgh", target.readText())
        server.takeRequest()
        server.takeRequest()
        assertNull("the restart asks for the whole file", server.takeRequest().headers["Range"])
    }

    @Test
    fun `a range from a file of a different length starts over`() = runTest {
        server.enqueue(cutOff("abcd", total = 8))
        server.enqueue(partial("efghij", start = 4, total = 10))
        server.enqueue(MockResponse(code = 200, body = "abcdefghij"))

        download()

        assertEquals("abcdefghij", target.readText())
    }

    @Test
    fun `a server error mid-download is retried`() = runTest {
        server.enqueue(cutOff("abcd", total = 8))
        server.enqueue(MockResponse(code = 503, body = """{"detail":"restarting"}"""))
        server.enqueue(partial("efgh", start = 4, total = 8))

        download()

        assertEquals("abcdefgh", target.readText())
    }

    @Test
    fun `a client error during a resume is final and leaves no file`() = runTest {
        // The signed URL expired, say. Asking again gets the same 404.
        server.enqueue(cutOff("abcd", total = 8))
        server.enqueue(MockResponse(code = 404, body = """{"detail":"Download job not found"}"""))

        val error = runCatching { download() }.exceptionOrNull()

        assertEquals(404, (error as ApiException).status)
        assertEquals(2, server.requestCount)
        assertFalse("a half-written build must never look like a build", target.exists())
    }

    @Test
    fun `it gives up after a few attempts and leaves no file`() = runTest {
        repeat(ApiClient.DOWNLOAD_ATTEMPTS) { attempt ->
            val sent = (attempt + 1) * 2
            if (attempt == 0) {
                server.enqueue(cutOff("x".repeat(sent), total = 100))
            } else {
                // Each resume makes a little progress and is cut off again.
                server.enqueue(
                    MockResponse.Builder()
                        .code(206)
                        .body("x".repeat(2))
                        .setHeader("Content-Length", 100 - attempt * 2)
                        .setHeader("Content-Range", "bytes ${attempt * 2}-99/100")
                        .onResponseEnd(SocketEffect.ShutdownConnection)
                        .build()
                )
            }
        }

        val error = runCatching { download() }.exceptionOrNull()

        assertTrue(error is ApiException)
        assertEquals(0, (error as ApiException).status)
        assertEquals(ApiClient.DOWNLOAD_ATTEMPTS, server.requestCount)
        assertFalse(target.exists())
    }

    @Test
    fun `a download that does not fit is refused before a byte is written`() = runTest {
        freeBytes = 3
        server.enqueue(MockResponse(code = 200, body = "abcdefgh"))

        val error = runCatching { download() }.exceptionOrNull()

        assertTrue(error is NotEnoughSpaceException)
        assertEquals("what has to be freed: 8 to come, 3 free", 5L, (error as NotEnoughSpaceException).bytesNeeded)
        assertEquals("not retried: more attempts will not make room", 1, server.requestCount)
        assertFalse(target.exists())
    }

    @Test
    fun `a resume only needs room for the bytes still missing`() = runTest {
        server.enqueue(cutOff("abcd", total = 8))
        server.enqueue(partial("efgh", start = 4, total = 8))
        // Room for the four bytes still to come, not for all eight again.
        var calls = 0
        val tight = ApiClient(
            client = OkHttpClient(),
            baseUrl = { server.url("/").toString().trimEnd('/') },
            token = { null },
            reserveSpace = { _, bytes -> if (calls++ == 0) 0L else (bytes - 4L).coerceAtLeast(0) },
            resumeDelayMillis = { 0L },
        )

        tight.download("/file", target) { _, _ -> }

        assertEquals("abcdefgh", target.readText())
    }

    @Test
    fun `every call starts from an empty file`() = runTest {
        // Bytes at this path from an earlier call may be a different build: a
        // re-enqueued job is signed again.
        target.writeText("stale-bytes-from-before")
        server.enqueue(MockResponse(code = 200, body = "fresh"))

        download()

        assertEquals("fresh", target.readText())
        assertNull(server.takeRequest().headers["Range"])
    }

    @Test
    fun `space for a restart is reserved only after the stale bytes are gone`() = runTest {
        // A resume answered with the whole file (the build changed on the server)
        // must not count the discarded partial file against the room it needs.
        server.enqueue(cutOff("abcd", total = 8))
        server.enqueue(MockResponse(code = 200, body = "ABCDEFGH"))
        val client = ApiClient(
            client = OkHttpClient(),
            baseUrl = { server.url("/").toString().trimEnd('/') },
            token = { null },
            // Room for 8 bytes only while nothing else is on disk.
            reserveSpace = { _, bytes -> if (target.isFile && target.length() > 0) bytes else 0L },
            resumeDelayMillis = { 0L },
        )

        client.download("/file", target) { _, _ -> }

        assertEquals("ABCDEFGH", target.readText())
    }

    @Test
    fun `a file that cannot be created is a local failure, not a network one`() = runTest {
        // Retried as a dropped connection, this used to end five requests later
        // in "Check your connection".
        val notADirectory = File.createTempFile("blocker", ".bin").also { it.deleteOnExit() }
        target = File(notADirectory, "build.apk")
        server.enqueue(MockResponse(code = 200, body = "abcdefgh"))

        val error = runCatching { download() }.exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(error is ApiException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an expired signed link is renewed and the transfer resumes`() = runTest {
        // The signed URL lives ten minutes. A slow download that drops after that
        // gets a 404 on resume; a fresh link continues the same file.
        server.enqueue(cutOff("abcd", total = 8))
        server.enqueue(MockResponse(code = 404, body = """{"detail":"Download job not found"}"""))
        server.enqueue(partial("efgh", start = 4, total = 8))
        var refreshed = 0

        client().download("/file", target, refreshUrl = { refreshed++; "/file?sig=fresh" }) { _, _ -> }

        assertEquals("abcdefgh", target.readText())
        assertEquals(1, refreshed)
        server.takeRequest()
        server.takeRequest()
        val resumed = server.takeRequest()
        assertEquals("/file?sig=fresh", resumed.target)
        assertEquals("bytes=4-", resumed.headers["Range"])
    }

    @Test
    fun `a link that never worked is not renewed`() = runTest {
        // A 404 on the very first request means the job is gone, not that the
        // link aged out; asking for a new one would only get another 404.
        server.enqueue(MockResponse(code = 404, body = """{"detail":"Download job not found"}"""))
        var refreshed = 0

        val error = runCatching {
            client().download("/file", target, refreshUrl = { refreshed++; "/file?sig=fresh" }) { _, _ -> }
        }.exceptionOrNull()

        assertEquals(404, (error as ApiException).status)
        assertEquals(0, refreshed)
    }

    @Test
    fun `running out of space mid-write is told apart from other write errors`() {
        assertTrue(isOutOfSpace(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(isOutOfSpace(IOException("No space left on device")))
        assertTrue(isOutOfSpace(IOException("outer", IOException("write failed: ENOSPC"))))
        assertFalse(isOutOfSpace(IOException("write failed: EIO (I/O error)")))
    }

    @Test
    fun `content ranges parse, and anything malformed is refused`() {
        assertEquals(ContentRange(4, 7, 8), parseContentRange("bytes 4-7/8"))
        assertEquals(ContentRange(0, 99, null), parseContentRange("bytes 0-99/*"))
        assertNull(parseContentRange(null))
        assertNull(parseContentRange("bytes */8"))
        assertNull(parseContentRange("items 1-2/3"))
    }

    private fun sha256Hex(file: File) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun hex(digest: java.security.MessageDigest) = digest.digest().joinToString("") { "%02x".format(it) }

    @Test
    fun `download from another host sends no account headers`() = runTest {
        val foreign = MockWebServer()
        foreign.start()
        try {
            foreign.enqueue(MockResponse(code = 200, body = "payload"))

            client().download(foreign.url("/w.apk").toString(), target) { _, _ -> }

            val request = foreign.takeRequest()
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["X-Store-Version-Code"])
            assertEquals(0, server.requestCount)
            assertEquals("payload", target.readText())
        } finally {
            foreign.close()
        }
    }

    @Test
    fun `download from our own host sends the account headers`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "payload"))

        client().download(server.url("/w.apk").toString(), target) { _, _ -> }

        val request = server.takeRequest()
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertEquals("0", request.headers["X-Store-Version-Code"])
    }

    @Test
    fun `digest matches the file after a resumed transfer`() = runTest {
        server.enqueue(cutOff("abcd", total = 8))
        server.enqueue(partial("efgh", start = 4, total = 8))
        val digest = java.security.MessageDigest.getInstance("SHA-256")

        client().download("/file", target, digest = digest) { _, _ -> }

        assertEquals("abcdefgh", target.readText())
        assertEquals(sha256Hex(target), hex(digest))
    }

    @Test
    fun `digest matches the file after a restart from zero`() = runTest {
        server.enqueue(cutOff("abcd", total = 8))
        // The server ignores the Range and sends everything again.
        server.enqueue(MockResponse(code = 200, body = "ABCDEFGH"))
        val digest = java.security.MessageDigest.getInstance("SHA-256")

        client().download("/file", target, digest = digest) { _, _ -> }

        assertEquals("ABCDEFGH", target.readText())
        assertEquals(sha256Hex(target), hex(digest))
    }
}
