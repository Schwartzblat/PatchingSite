package site.androidmod.store.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import site.androidmod.patch.TestKeys
import site.androidmod.patch.apksigSigner
import site.androidmod.patch.manifestWith
import site.androidmod.patch.zip
import java.io.File
import java.security.MessageDigest

class SplitArchiveInfoTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val key by lazy { TestKeys.generate("user") }

    private fun apk(name: String, manifest: ByteArray): File =
        zip(File(folder.root, "$name.unsigned"), listOf("AndroidManifest.xml" to manifest, BLOB_ENTRY to BLOB), stored = true)

    private fun signed(name: String, manifest: ByteArray): File =
        File(folder.root, name).also { apksigSigner(key).sign(apk(name, manifest), it) }

    @Test
    fun a_signed_split_is_read_from_its_manifest_and_signature() {
        // PackageManager's archive parser refuses a split on its own, so the generic job's inspector reads splits this way.
        val split = signed("split_config.arm64_v8a.apk", manifestWith(PKG, versionCode = 7, split = "config.arm64_v8a"))

        assertEquals(ArchiveInfo(PKG, 7, setOf(sha256(key.cert.encoded))), splitArchiveInfo(split))
    }

    @Test
    fun a_split_that_does_not_verify_is_nothing() {
        // Unsigned.
        assertNull(splitArchiveInfo(apk("unsigned.apk", manifestWith(PKG, versionCode = 7, split = "config.xxhdpi"))))

        // Signed, then one byte of its content changed.
        val split = signed("split_config.xxhdpi.apk", manifestWith(PKG, versionCode = 7, split = "config.xxhdpi"))
        val bytes = split.readBytes()
        val at = indexOf(bytes, BLOB)
        bytes[at] = (bytes[at] + 1).toByte()
        split.writeBytes(bytes)

        assertNull(splitArchiveInfo(split))
    }

    @Test
    fun a_base_or_a_file_that_is_not_an_apk_is_nothing() {
        assertNull(splitArchiveInfo(signed("base.apk", manifestWith(PKG, versionCode = 7))))
        assertNull(splitArchiveInfo(File(folder.root, "junk.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }))
        assertNull(splitArchiveInfo(File(folder.root, "missing.apk")))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).first { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    private companion object {
        const val PKG = "com.example.app"
        const val BLOB_ENTRY = "assets/blob.bin"
        val BLOB = "the split's own content, stored uncompressed".toByteArray()

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
