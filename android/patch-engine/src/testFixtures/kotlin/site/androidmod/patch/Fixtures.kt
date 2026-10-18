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

/** A binary manifest with the given facts; [components] are (tag, android:name) pairs under `<application>`. */
fun manifestWith(
    pkg: String,
    versionCode: Int? = 1,
    versionName: String? = null,
    split: String? = null,
    sharedUserId: String? = null,
    minSdk: Int? = null,
    targetSdk: Int? = null,
    components: List<Pair<String, String>> = emptyList(),
    applicationName: String? = null,
    /** `<provider>`s as (android:name, android:authorities). */
    providers: List<Pair<String, String>> = emptyList(),
): ByteArray {
    val am = com.reandroid.arsc.chunk.xml.AndroidManifestBlock.empty()
    am.packageName = pkg
    if (versionCode != null) am.versionCode = versionCode
    if (versionName != null) am.versionName = versionName
    if (split != null) am.manifestElement.getOrCreateAttribute("split", 0).setValueAsString(split)
    if (sharedUserId != null) {
        am.manifestElement.getOrCreateAndroidAttribute("sharedUserId", 0x0101000b).setValueAsString(sharedUserId)
    }
    if (minSdk != null) am.minSdkVersion = minSdk
    if (targetSdk != null) am.targetSdkVersion = targetSdk
    val app = am.getOrCreateApplicationElement()
    if (applicationName != null) app.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(applicationName)
    for ((tag, name) in components) {
        app.newElement(tag).getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(name)
    }
    for ((name, authorities) in providers) {
        val p = app.newElement("provider")
        p.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(name)
        p.getOrCreateAndroidAttribute("authorities", 0x01010018).setValueAsString(authorities)
    }
    am.refreshFull()
    return am.bytes
}
