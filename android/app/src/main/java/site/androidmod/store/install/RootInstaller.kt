package site.androidmod.store.install

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Silent install on a rooted device.
 *
 * Each APK is piped to `pm install-write ... -` over the su process's stdin, so
 * nothing is ever extracted to disk - the same property the PackageInstaller path has.
 */
class RootInstaller(
    private val context: Context,
    private val trustedSigners: () -> Set<String> = { emptySet() },
) : Installer {

    override val isAvailable: Boolean by lazy {
        runCatching { runSu("id").contains("uid=0") }.getOrDefault(false)
    }

    // No system dialog on this path, so nothing to confirm.
    override suspend fun install(file: File, confirmation: InstallConfirmation): InstallOutcome = withContext(Dispatchers.IO) {
        val contents = try {
            BundleReader.read(file)
        } catch (error: BundleException) {
            return@withContext InstallOutcome.Failure(
                error.message ?: "This build could not be read.", InstallFailure.UNKNOWN
            )
        }

        installContents(file, contents, emptySet())
    }

    override suspend fun installApks(
        apks: List<File>,
        confirmation: InstallConfirmation,
        extraTrusted: Set<String>,
    ): InstallOutcome = withContext(Dispatchers.IO) {
        val contents = try {
            apkSetContents(apks)
        } catch (error: BundleException) {
            return@withContext InstallOutcome.Failure(
                error.message ?: "These files could not be installed.", InstallFailure.UNKNOWN
            )
        }
        installContents(apks.first(), contents, extraTrusted)
    }

    private fun installContents(file: File, contents: BuildContents, extraTrusted: Set<String>): InstallOutcome {
        // Nothing is piped to `pm` unless it is signed by our key (or a signer named in
        // extraTrusted, for a restore). Especially important here: the root path
        // installs silently, with no system dialog the user could use to reject a
        // swapped build.
        SignatureVerifier.verify(context, file, contents, trustedSigners() + extraTrusted)
            ?.let { return it }

        // Guarded for the same reason SessionInstaller guards createSession: this is
        // the first su call, and streamToSu can now THROW on timeout rather than
        // hanging forever. install() is contracted to RESOLVE to an outcome, so an
        // unanswered root-grant prompt must become a Failure, not an escaping
        // exception. Leaving it unguarded traded an infinite hang for a crash.
        val created = try {
            runSu("pm install-create -r")
        } catch (error: Throwable) {
            return InstallErrors.failure(error.message)
        }
        val sessionId = parseSessionId(created)
            ?: return InstallOutcome.Failure(
                "Could not open a root install session: ${created.trim()}", InstallFailure.UNKNOWN
            )

        return try {
            contents.parts.forEach { part ->
                // -S is omitted when the zip recorded no size (-1); pm then reads
                // stdin to EOF. `-S -1` is a malformed command, and SessionInstaller
                // already handles the same sentinel.
                val size = if (part.size >= 0) "-S ${part.size} " else ""
                val output = streamToSu(
                    "pm install-write $size$sessionId ${part.sessionName} -",
                ) { sink -> BundleReader.openPart(file, part).use { it.copyTo(sink) } }

                if (!output.trimStart().startsWith("Success")) {
                    runCatching { runSu("pm install-abandon $sessionId") }
                    return outcomeOf(output)
                }
            }
            outcomeOf(runSu("pm install-commit $sessionId"))
        } catch (error: CancellationException) {
            // Never swallowed: cancelling an install must propagate, not be reported
            // as a failed one. Cleanup still runs. SessionInstaller has had this arm
            // since it was written; its sibling was missed, and a symmetric pair
            // that behaves asymmetrically is exactly how this stays broken.
            runCatching { runSu("pm install-abandon $sessionId") }
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Root install failed", error)
            runCatching { runSu("pm install-abandon $sessionId") }
            InstallOutcome.Failure("The root install failed: ${error.message}", InstallFailure.UNKNOWN)
        }
    }

    private fun runSu(command: String): String = streamToSu(command) { }

    /**
     * Runs `su -c <command>`, letting [writeStdin] feed the process.
     *
     * Output is drained on a second thread WHILE stdin is written, which is not an
     * optimisation - it is what stops the install deadlocking. An 85MB split does
     * not fit in the kernel pipe buffer (~64KB), so if `pm` writes anything before
     * it has consumed all of stdin, `pm` blocks on its write while this thread is
     * still blocked on its own. Both wait forever. The device verification of this
     * command sequence used `cat file | pm ...`, where the shell supplies two
     * independent processes, so it did not cover this path.
     */
    private fun streamToSu(command: String, writeStdin: (OutputStream) -> Unit): String {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        try {
            val output = StringBuilder()
            val drain = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.forEachLine { line -> output.appendLine(line) }
                    }
                }
            }
            drain.isDaemon = true
            drain.start()

            process.outputStream.use(writeStdin)

            // A hung su - a root-grant prompt left unanswered, say - must not wedge
            // the caller forever with no way out.
            if (!process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw BundleException("The root install did not respond in time.")
            }
            drain.join(DRAIN_JOIN_MILLIS)
            // join() only establishes happens-before when it returned because the
            // thread TERMINATED. On the timeout path the drain thread is still
            // appending to `output`, so reading it here is a data race on a
            // StringBuilder - the result can be torn, or short, and a truncated
            // "Succe" would be read as a failure (or worse, a truncated failure as
            // a success). Refuse to interpret output nobody can safely read.
            if (drain.isAlive) {
                throw BundleException("The root install did not finish reporting in time.")
            }
            return output.toString()
        } finally {
            // A throw from writeStdin - a corrupt zip entry, a truncated download -
            // must not strand a root shell. Across a 19-split install that is 19.
            if (process.isAlive) process.destroyForcibly()
        }
    }

    companion object {
        private const val TAG = "PatchedStore"

        /** Generous: an 85MB split write plus a commit, on a slow device. */
        private const val SU_TIMEOUT_SECONDS = 300L
        private const val DRAIN_JOIN_MILLIS = 5_000L

        private val SESSION_ID = Regex("""\[(\d+)]""")

        /** `pm install-create` prints: Success: created install session [2097227766] */
        fun parseSessionId(output: String): Int? =
            SESSION_ID.find(output)?.groupValues?.get(1)?.toIntOrNull()

        /** `pm` prints `Success` or `Failure [REASON]`; anything else is a failure. */
        fun outcomeOf(output: String): InstallOutcome {
            val text = output.trim()
            if (text.startsWith("Success")) return InstallOutcome.Success
            return InstallErrors.failure(text)
        }
    }
}
