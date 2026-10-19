package site.androidmod.store.generic

import java.io.File
import java.io.IOException
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

/**
 * Every copy in [saved] is in [dir] with its recorded size and [sha256]; an empty list, or a file
 * that can't be read, is not. Blocking, and interruptible like [sha256Of].
 */
fun savedCopiesIntact(dir: File, saved: List<SavedApk>, sha256: (File) -> String = ::sha256Of): Boolean =
    saved.isNotEmpty() && saved.all { apk ->
        val file = File(dir, apk.name)
        try {
            file.isFile && file.length() == apk.size && sha256(file) == apk.sha256
        } catch (error: IOException) {
            false
        }
    }
