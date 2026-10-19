package site.androidmod.store.generic

import java.io.File
import java.security.MessageDigest

private const val BUFFER_BYTES = 256 * 1024

/**
 * The lowercase hex SHA-256 of [file]'s bytes. Blocking. Between chunks it stops with an
 * InterruptedException once the thread is interrupted, so a runInterruptible caller can cancel it.
 */
fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
