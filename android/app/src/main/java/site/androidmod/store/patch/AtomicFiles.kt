package site.androidmod.store.patch

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Writes [bytes] to [target] through a temp file in the same directory, so a reader never sees half a file. The temp file is synced before the move, so a
 * record that was written is still there after a crash. */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    target.absoluteFile.parentFile?.mkdirs()
    val temp = File(target.absoluteFile.parentFile, target.name + ".tmp")
    try {
        java.io.FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temp.delete()
    }
}
