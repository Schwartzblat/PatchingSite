package site.androidmod.store.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PatchInfoTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun apk(entryName: String?, content: String): File {
        val file = folder.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(1)
            zip.closeEntry()
            if (entryName != null) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `readPatchInfo reads the asset`() {
        val file = apk(
            "assets/patcher/patch-info.json",
            """{"patcher_version":"1.2","revision":3,"dex_sha256":"ab","features_off":["a","b"],"extra":1}""",
        )
        assertEquals(PatchInfo("1.2", 3, "ab", listOf("a", "b")), readPatchInfo(file))
    }

    @Test
    fun `missing asset is null`() {
        assertNull(readPatchInfo(apk(null, "")))
    }

    @Test
    fun `garbled asset is null`() {
        assertNull(readPatchInfo(apk("assets/patcher/patch-info.json", "not json {")))
    }

    @Test
    fun `not a zip is null`() {
        val file = folder.newFile().apply { writeText("nope") }
        assertNull(readPatchInfo(file))
    }
}
