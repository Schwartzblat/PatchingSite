package site.androidmod.patch

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

/** A fixture under `patch.fixtures`, or null when it is absent (callers `assumeTrue`). */
fun fixtureFile(name: String): File? {
    val dir = System.getProperty("patch.fixtures") ?: return null
    return File(dir, name).takeIf { it.isFile }
}

/**
 * Writes a temp zip of [entries] in order. Duplicate names are allowed (commons-compress
 * writes them; java.util.zip refuses).
 */
fun zip(file: File, entries: List<Pair<String, ByteArray>>, stored: Boolean = false): File {
    ZipArchiveOutputStream(file).use { out ->
        for ((name, bytes) in entries) {
            val e = ZipArchiveEntry(name)
            if (stored) {
                e.method = ZipEntry.STORED
                e.size = bytes.size.toLong()
                e.compressedSize = bytes.size.toLong()
                e.crc = java.util.zip.CRC32().also { it.update(bytes) }.value
            } else {
                e.method = ZipEntry.DEFLATED
            }
            out.putArchiveEntry(e)
            out.write(bytes)
            out.closeArchiveEntry()
        }
    }
    return file
}
