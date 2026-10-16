package site.androidmod.store.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Mirrors client/src/lib/api.js: one place that knows the base URL, attaches
 * credentials, and turns every failure into an [ApiException] carrying the
 * server's own message.
 *
 * [baseUrl] and [token] are lambdas rather than the stores themselves so this
 * class stays a plain JVM object that MockWebServer can drive.
 */
class ApiClient(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: () -> String,
    private val token: () -> String?,
    /**
     * Makes room for `bytes` more under `directory` and returns how many bytes it
     * is short - 0 when they fit. The default only compares against the JVM's free
     * space; Container supplies one backed by StorageManager.allocateBytes, which
     * clears other apps' caches to make room, so a phone that is merely full of
     * cache is not told it is out of space.
     */
    private val reserveSpace: (directory: File, bytes: Long) -> Long = ::usableSpaceShortfall,
    /** The wait before resume attempt `n` (1-based). Tests pass zero. */
    private val resumeDelayMillis: (attempt: Int) -> Long = ::defaultResumeDelayMillis,
    /** Sent as X-Store-Version-Code to our server so it can refuse a store too old for a feature. */
    private val storeVersionCode: () -> Long = { 0L },
) {
    val json = Json { ignoreUnknownKeys = true }

    suspend fun get(path: String): String = execute(request(path).get().build())

    suspend fun post(path: String, body: String? = null): String {
        val builder = request(path)
        builder.post((body ?: "").toRequestBody(JSON_MEDIA_TYPE))
        return execute(builder.build())
    }

    /**
     * Streams [url] to [target], resuming an interrupted transfer where it stopped.
     *
     * The artifacts are 38-85 MB and phones drop connections - a Wi-Fi handover, a
     * lift, a tunnel. Starting over each time wasted the bytes already on disk, and
     * before this a single drop failed the download outright. Now a dropped
     * connection or a 5xx is retried up to [DOWNLOAD_ATTEMPTS] times in all, each
     * retry asking only for the missing bytes with `Range: bytes=<n>-`. The server
     * honours that with 206 and a Content-Range (FileResponse does, for both the
     * patched-build and the store-update endpoints).
     *
     * A resume is only appended when it provably continues the same file:
     * - `If-Range` carries the first response's ETag (or Last-Modified), so a build
     *   that changed on the server comes back whole, as a 200, instead of as the
     *   tail of a different file.
     * - The 206's Content-Range must start exactly where the file ends and report
     *   the same total length as before.
     * Anything else - a 200, a range that does not line up, a 416 - starts the file
     * over from zero. Stitching two builds together would produce an APK that fails
     * at install time with an error nobody could explain.
     *
     * A 4xx is final: it is the server's answer, and asking again returns it again.
     * The one exception is a 404 from a link that has already served bytes, which
     * is how the server answers a signed URL past its ten-minute life: a slow
     * download that drops late would otherwise lose everything it had. When the
     * caller supplies [refreshUrl], the transfer asks it for a fresh link and
     * resumes on that; If-Range still guards against the build having changed. A
     * link that never worked is not renewed - its 404 means the job is gone.
     *
     * Space is checked against the bytes still to come before any are written, and
     * a disk that fills up anyway (ENOSPC) is reported the same way, as
     * [NotEnoughSpaceException] - never retried, because more attempts will not
     * make room.
     *
     * Any failure deletes the partial file: a half-written 85 MB bundle that looks
     * like a build is worse than no file at all. For the same reason every call
     * starts from an empty file rather than resuming whatever an earlier call left
     * at [target]: a re-enqueued job is re-signed, so those bytes may belong to a
     * different build, and nothing at this level could tell.
     *
     * When [digest] is given it is fed every byte kept in [target], so on success it
     * holds the file's hash; it is reset whenever the transfer starts over from zero.
     *
     * The account's bearer token and the store version go only to our own server
     * (same scheme, host and port as the base URL); a link to any other host - the
     * official WhatsApp CDN, say - gets a bare request.
     */
    suspend fun download(
        url: String,
        target: File,
        refreshUrl: (suspend () -> String?)? = null,
        digest: MessageDigest? = null,
        onProgress: (bytesRead: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var current = absolute(url)
        target.parentFile?.mkdirs()
        target.delete()
        digest?.reset()
        val transfer = Transfer()
        try {
            var failures = 0
            while (true) {
                val retryable = try {
                    fetchOnce(current, target, transfer, digest, onProgress)
                    return@withContext
                } catch (error: NotEnoughSpaceException) {
                    throw error
                } catch (error: LocalWriteException) {
                    // The disk, not the network: another attempt fails the same way.
                    throw error.cause
                } catch (error: IOException) {
                    // Also a range that did not line up; see fetchOnce.
                    ApiException(0, INTERRUPTED_MESSAGE)
                } catch (error: ApiException) {
                    if (error.status == HTTP_NOT_FOUND && transfer.linkWorked && refreshUrl != null) {
                        // The signed link aged out mid-download. Not counted as a
                        // failure - the network is fine - and it cannot loop: the
                        // next renewal needs the new link to have worked first.
                        current = absolute(refreshUrl() ?: throw error)
                        transfer.linkWorked = false
                        continue
                    }
                    if (!error.isTransient) throw error
                    error
                }
                if (++failures >= DOWNLOAD_ATTEMPTS) throw retryable
                delay(resumeDelayMillis(failures))
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /** What earlier attempts of one [download] call learned about the file. */
    private class Transfer {
        /** The full length, once a response has said; -1 until then. */
        var total = -1L

        /** ETag or Last-Modified of the file being assembled, for If-Range. */
        var validator: String? = null

        /** Whether the current link has been answered with bytes; see refreshUrl. */
        var linkWorked = false
    }

    private fun absolute(url: String) = if (url.startsWith("http")) url else baseUrl() + url

    /** Raised for a failed write to [target], as opposed to a failed read from the network. */
    private class LocalWriteException(override val cause: IOException) : IOException(cause)

    /** A full disk gets its own exception; any other local write failure is final as it is. */
    private fun localWriteFailure(error: IOException, missing: Long): IOException =
        if (isOutOfSpace(error)) NotEnoughSpaceException(missing) else LocalWriteException(error)

    /**
     * One request: everything from the current end of [target] onwards. Returns
     * when the file is complete; throws IOException for anything worth another
     * attempt, after putting [target] in the state that attempt should start from.
     */
    private fun fetchOnce(
        url: String,
        target: File,
        transfer: Transfer,
        digest: MessageDigest?,
        onProgress: (Long, Long) -> Unit,
    ) {
        val offset = if (target.isFile) target.length() else 0L
        if (transfer.total >= 0 && offset == transfer.total) return

        val builder = Request.Builder().url(url).get()
        if (isOurs(url)) signed(builder)
        if (offset > 0) {
            builder.header("Range", "bytes=$offset-")
            transfer.validator?.let { builder.header("If-Range", it) }
        }
        // An IOException from execute() propagates: a request that got no answer
        // is exactly what the caller retries.
        client.newCall(builder.build()).execute().use { response ->
            val append = when (response.code) {
                HTTP_PARTIAL_CONTENT -> {
                    val range = parseContentRange(response.header("Content-Range"))
                    val consistent = offset > 0 && range != null && range.start == offset &&
                        (transfer.total < 0 || range.total == null || range.total == transfer.total)
                    if (!consistent) {
                        target.delete()
                        throw IOException("The server's range does not continue the file; starting over")
                    }
                    range.total?.let { transfer.total = it }
                    true
                }
                HTTP_RANGE_NOT_SATISFIABLE -> {
                    target.delete()
                    throw IOException("The server refused the range; starting over")
                }
                else -> {
                    if (!response.isSuccessful) {
                        val body = runCatching { response.body.string() }.getOrNull()
                        throw ApiException(response.code, ApiErrors.messageFrom(body, response.code))
                    }
                    // The whole file, even if a range was asked for: the server
                    // ignored it, or If-Range said the file changed. Start over.
                    transfer.total = response.body.contentLength()
                    false
                }
            }
            transfer.linkWorked = true
            // A weak ETag may not be used in If-Range, so fall back to the date.
            transfer.validator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                ?: response.header("Last-Modified")
                ?: transfer.validator

            // A restart drops the stale bytes BEFORE asking for room, or they count
            // against it: a 60 MB partial file would make an 85 MB build that fits
            // once it is gone ask the user to free space it does not need.
            if (!append) {
                target.delete()
                digest?.reset()
            }
            val written = if (append) offset else 0L
            val remaining = response.body.contentLength()
            if (remaining > 0) {
                val shortfall = reserveSpace(target.absoluteFile.parentFile, remaining)
                if (shortfall > 0) throw NotEnoughSpaceException(shortfall)
            }

            var read = written
            // Opening the file is a local write like any other: a failure here -
            // ENOSPC creating it, a directory that is not one - is not the network
            // and must not be retried as a dropped connection.
            val output = try {
                FileOutputStream(target, append)
            } catch (error: IOException) {
                throw localWriteFailure(error, missing = if (remaining > 0) remaining else -1L)
            }
            response.body.byteStream().use { source ->
                output.use { sink ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        // A read failure is the network and propagates for a retry.
                        val count = source.read(buffer)
                        if (count == -1) break
                        try {
                            sink.write(buffer, 0, count)
                        } catch (error: IOException) {
                            throw localWriteFailure(error, missing = if (transfer.total >= 0) transfer.total - read else -1L)
                        }
                        digest?.update(buffer, 0, count)
                        read += count
                        onProgress(read, transfer.total)
                    }
                }
            }
            // OkHttp already fails a body shorter than its Content-Length; this
            // catches a 206 whose range ended early, and anything else that left
            // the file a different size from the one the server announced.
            if (transfer.total >= 0 && read != transfer.total) {
                throw IOException("Received $read of ${transfer.total} bytes")
            }
        }
    }

    /**
     * Size of [url] from a HEAD request, or null when the server sends no
     * Content-Length. The same header rule as [download] applies.
     */
    suspend fun size(url: String): Long? = withContext(Dispatchers.IO) {
        val resolved = absolute(url)
        val builder = Request.Builder().url(resolved).head()
        if (isOurs(resolved)) signed(builder)
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (error: IOException) {
            throw ApiException(0, ApiErrors.messageFrom(null, 0))
        }
        response.use {
            if (!it.isSuccessful) throw ApiException(it.code, ApiErrors.messageFrom(null, it.code))
            it.header("Content-Length")?.toLongOrNull()?.takeIf { length -> length >= 0 }
        }
    }

    /**
     * GETs [path] from our server into [target], replacing it atomically (a temp
     * file next to it, then a rename) so a cut transfer never leaves a half file
     * where a good one was. [ifNoneMatch] is sent verbatim as If-None-Match. A 304
     * returns null and leaves [target] alone; a 200 returns the ETag without quotes,
     * or the SHA-256 of the bytes when the server sent none. A server that ignores
     * If-None-Match simply answers 200 with the whole file, which is handled the same.
     */
    suspend fun fetchFile(path: String, target: File, ifNoneMatch: String?): String? =
        withContext(Dispatchers.IO) {
            val builder = request(path).get()
            ifNoneMatch?.let { builder.header("If-None-Match", it) }
            target.absoluteFile.parentFile?.mkdirs()
            val temp = File(target.absoluteFile.parentFile, target.name + ".part")
            try {
                val response = try {
                    client.newCall(builder.build()).execute()
                } catch (error: IOException) {
                    throw ApiException(0, ApiErrors.messageFrom(null, 0))
                }
                response.use {
                    if (it.code == HTTP_NOT_MODIFIED) return@withContext null
                    if (!it.isSuccessful) {
                        val body = runCatching { it.body.string() }.getOrNull()
                        throw ApiException(it.code, ApiErrors.messageFrom(body, it.code), body)
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    try {
                        it.body.byteStream().use { source ->
                            FileOutputStream(temp).use { sink ->
                                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count == -1) break
                                    sink.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                }
                            }
                        }
                    } catch (error: IOException) {
                        throw ApiException(0, ApiErrors.messageFrom(null, 0))
                    }
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    it.header("ETag")?.removePrefix("W/")?.trim('"')?.takeIf { tag -> tag.isNotEmpty() }
                        ?: digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                }
            } finally {
                temp.delete()
            }
        }

    /** Whether [url] is on the same scheme, host and port as the base URL. */
    private fun isOurs(url: String): Boolean {
        val candidate = url.toHttpUrlOrNull() ?: return false
        val base = baseUrl().toHttpUrlOrNull() ?: return false
        return candidate.scheme == base.scheme && candidate.host == base.host && candidate.port == base.port
    }

    private fun request(path: String) = signed(Request.Builder().url(baseUrl() + path))

    /** Credentials and store version: only ever for requests to our own server. */
    private fun signed(builder: Request.Builder): Request.Builder {
        token()?.let { builder.header("Authorization", "Bearer $it") }
        builder.header("X-Store-Version-Code", storeVersionCode().toString())
        return builder
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw ApiException(0, ApiErrors.messageFrom(null, 0))
        }
        response.use {
            val body = runCatching { it.body.string() }.getOrNull()
            if (!it.isSuccessful) throw ApiException(it.code, ApiErrors.messageFrom(body, it.code), body)
            body.orEmpty()
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_NOT_MODIFIED = 304
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /**
         * Requests per download, the first included. With the default backoff the
         * retries span about 15 seconds - a network handover or a server restart -
         * before the failure is reported.
         */
        const val DOWNLOAD_ATTEMPTS = 5

        const val INTERRUPTED_MESSAGE = "The download was interrupted. Check your connection and try again."

        /** 1, 2, 4, 8 seconds. */
        fun defaultResumeDelayMillis(attempt: Int): Long = 1000L shl (attempt - 1).coerceIn(0, 3)

        /**
         * The JVM's answer, for tests and any caller without a Context. Lint's
         * UsableSpace check is right that it undercounts on Android - which is why
         * the app's own ApiClient, built in Container, never uses it.
         */
        @Suppress("UsableSpace")
        fun usableSpaceShortfall(directory: File, bytes: Long): Long =
            (bytes - directory.usableSpace).coerceAtLeast(0)

        /** Read timeout is generous: the server re-signs a build before it answers. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** Deserialise a response body, or fail with a message the UI can show. */
inline fun <reified T> ApiClient.decode(body: String): T =
    runCatching { json.decodeFromString<T>(body) }.getOrElse {
        throw ApiException(0, "The server sent a response this app could not read.")
    }
