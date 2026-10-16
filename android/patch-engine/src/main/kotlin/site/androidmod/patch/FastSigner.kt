package site.androidmod.patch

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [ApkSigner] that runs the fastsigner binary against a PKCS#12 [keystore].
 *
 * Secrets never touch argv: the store password goes to the child's stdin, and the optional
 * [keyPassword] goes into the child's own environment (`PATCH_KEY_PASS`) only.
 */
class FastSigner(
    private val binary: File,
    private val keystore: File,
    private val password: CharArray,
    private val alias: String? = null,
    private val keyPassword: CharArray? = null,
) : ApkSigner {

    override fun sign(unsigned: File, signed: File) {
        signed.delete()
        val cmd = mutableListOf(binary.path, "--ks", keystore.path, "--ks-pass", "stdin")
        if (alias != null) cmd += listOf("--ks-key-alias", alias)
        if (keyPassword != null) cmd += listOf("--key-pass", "env:$KEY_PASS_ENV")
        cmd += listOf("--out", signed.path, unsigned.path)

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        if (keyPassword != null) pb.environment()[KEY_PASS_ENV] = String(keyPassword)
        val process = try {
            pb.start()
        } catch (e: IOException) {
            throw fail(signed, "cannot run ${binary.path}: ${e.message}")
        }
        // stdout and stderr are merged and drained concurrently so a full pipe cannot stall it.
        var output = ""
        val reader = Thread { output = process.inputStream.readBytes().toString(Charsets.UTF_8) }
            .apply { isDaemon = true; start() }
        try {
            try {
                process.outputStream.use { it.write((String(password) + "\n").toByteArray()) }
            } catch (_: IOException) {
                // Child exited before reading stdin; its exit code and output explain why.
            }
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly().waitFor()
                reader.join(2000)
                throw fail(signed, "fastsigner timed out after $TIMEOUT_MINUTES minutes")
            }
            reader.join(5000)
            if (process.exitValue() != 0) {
                throw fail(signed, "fastsigner exited ${process.exitValue()}: ${output.trim()}")
            }
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw fail(signed, "interrupted while signing")
        }
        if (!signed.isFile) throw fail(signed, "fastsigner produced no output: ${output.trim()}")
    }

    private fun fail(signed: File, message: String): PatchException.SignFailed {
        signed.delete()
        return PatchException.SignFailed(message)
    }

    private companion object {
        const val KEY_PASS_ENV = "PATCH_KEY_PASS"
        const val TIMEOUT_MINUTES = 5L
    }
}
