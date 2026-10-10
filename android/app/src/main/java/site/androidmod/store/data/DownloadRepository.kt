package site.androidmod.store.data

import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.math.min
import kotlin.math.pow

sealed interface DownloadProgress {
    /** The server is re-signing the build. [fraction] is its own 0..1 progress. */
    data class Preparing(val fraction: Double, val message: String?) : DownloadProgress

    /** Bytes are moving. [total] is -1 when the server sent no Content-Length. */
    data class Transferring(val bytesRead: Long, val total: Long) : DownloadProgress
}

class DownloadRepository(private val api: ApiClient) {

    /**
     * Enqueue, poll until ready, then stream the artifact to [target].
     *
     * Re-signing takes minutes, so this is long-running by nature and belongs to a
     * foreground service, never to a screen.
     */
    suspend fun download(
        packageName: String,
        version: String,
        target: File,
        onProgress: (DownloadProgress) -> Unit,
    ): File {
        var ready: JobStatus? = null
        var attemptsLeft = ENQUEUE_ATTEMPTS

        while (ready == null) {
            attemptsLeft--
            val job = create(packageName, version)
            ready = try {
                awaitReady(job.jobId, onProgress)
            } catch (error: ApiException) {
                // 404 means the in-process job registry forgot the job, which a
                // server restart is enough to cause. Enqueue once more; anything
                // else is a real failure.
                if (error.status == 404 && attemptsLeft > 0) null else throw error
            }
        }

        val url = ready.downloadUrl
            ?: throw ApiException(0, "The build finished but the server returned no download link.")
        val jobId = ready.jobId
        api.download(
            url,
            target,
            // Every status read signs a fresh link, so this is how a transfer that
            // outlives its ten-minute link resumes instead of starting over.
            refreshUrl = { status(jobId).downloadUrl },
        ) { read, total -> onProgress(DownloadProgress.Transferring(read, total)) }
        return target
    }

    suspend fun create(packageName: String, version: String): CreatedJob {
        val body = api.json.encodeToString(DownloadRequest(packageName, version))
        return api.decode(api.post("/api/downloads", body))
    }

    suspend fun status(jobId: String): JobStatus = api.decode(api.get("/api/downloads/$jobId"))

    private suspend fun awaitReady(jobId: String, onProgress: (DownloadProgress) -> Unit): JobStatus {
        var attempt = 0
        var failuresInARow = 0
        while (true) {
            // Checked before the first sleep so a cache hit is not made to wait.
            val status = try {
                status(jobId).also { failuresInARow = 0 }
            } catch (error: ApiException) {
                // A poll is a read of state the server keeps, so asking again is
                // always safe - and one dropped request used to fail a build the
                // server was still happily preparing: a phone moving from Wi-Fi to
                // mobile data, or nginx answering 502 for the seconds uvicorn takes
                // to restart. Only an unbroken run of failures means the server is
                // really gone. A 4xx is an answer rather than a blip and is not
                // retried; 404 in particular is the caller's cue to enqueue again.
                if (!error.isTransient || ++failuresInARow > POLL_RETRIES) throw error
                null
            }
            when (status?.state) {
                null -> Unit
                JobStatus.READY -> return status
                JobStatus.FAILED -> throw ApiException(
                    0, status.message ?: "The build failed. Please try again."
                )
                else -> onProgress(DownloadProgress.Preparing(status.progress, status.message))
            }
            // Retries share the poll's own backoff: it already grows and caps at
            // five seconds, which is the right pace for waiting out an outage too.
            delay(pollDelayMillis(attempt))
            attempt++
        }
    }

    companion object {
        private const val ENQUEUE_ATTEMPTS = 2

        /**
         * Consecutive transient failures tolerated while polling. With the poll
         * backoff that is roughly 20 seconds of outage - long enough for a network
         * handover or a server restart, short enough that a server that is really
         * down is reported instead of spun on.
         */
        const val POLL_RETRIES = 6

        /** Matches the web client: min(1000 * 1.5^n, 5000) ms. */
        fun pollDelayMillis(attempt: Int): Long =
            min(1000.0 * 1.5.pow(attempt), 5000.0).toLong()
    }
}
