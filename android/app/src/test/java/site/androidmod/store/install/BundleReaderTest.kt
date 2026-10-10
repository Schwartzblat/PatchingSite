package site.androidmod.store.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundleReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val file = folder.newFile(name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (entryName, bytes) ->
                out.putNextEntry(ZipEntry(entryName))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    /** A minimal stand-in for a real APK: a zip with an AndroidManifest.xml in it. */
    private fun apkBytes(payload: String) = ByteArrayZip.of(
        "AndroidManifest.xml" to payload.toByteArray(),
    )

    private object ByteArrayZip {
        fun of(vararg entries: Pair<String, ByteArray>): ByteArray {
            val buffer = java.io.ByteArrayOutputStream()
            ZipOutputStream(buffer).use { out ->
                entries.forEach { (name, bytes) ->
                    out.putNextEntry(ZipEntry(name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
            return buffer.toByteArray()
        }
    }

    @Test
    fun `a plain apk yields one part that streams the file itself`() {
        val file = zip("build.apk", mapOf("AndroidManifest.xml" to "manifest".toByteArray()))

        val contents = BundleReader.read(file)

        assertFalse(contents.isBundle)
        assertEquals(1, contents.parts.size)
        assertNull("a single apk streams the file, not an entry", contents.parts[0].zipEntry)
        assertEquals(file.length(), contents.parts[0].size)
    }

    @Test
    fun `a bundle yields one part per apk entry`() {
        val file = zip("build.xapk", mapOf(
            "com.tranzmate.apk" to apkBytes("base"),
            "config.arm64_v8a.apk" to apkBytes("abi"),
            "config.en.apk" to apkBytes("lang"),
            "icon.png" to ByteArray(32),
            "manifest.json" to """{"package_name":"com.tranzmate"}""".toByteArray(),
        ))

        val contents = BundleReader.read(file)

        assertTrue(contents.isBundle)
        assertEquals(3, contents.parts.size)
        assertEquals("com.tranzmate", contents.packageName)
        assertTrue(contents.parts.none { it.zipEntry == null })
        assertTrue(contents.parts.none { it.zipEntry == "icon.png" })
    }

    @Test
    fun `the largest apk is written first as base`() {
        val file = zip("build.xapk", mapOf(
            "config.en.apk" to apkBytes("small"),
            "com.tranzmate.apk" to apkBytes("the base apk is by far the largest of the set"),
        ))

        val parts = BundleReader.read(file).parts

        assertEquals("base", parts.first().sessionName)
        assertEquals("com.tranzmate.apk", parts.first().zipEntry)
    }

    @Test
    fun `a split literally named base does not collide with the base slot`() {
        // The largest entry always takes the literal name "base", so a split that is
        // itself called base.apk has to be renamed rather than clash with it.
        val file = zip("build.xapk", mapOf(
            "com.app.apk" to apkBytes("the largest entry by a wide margin, this is the base"),
            "base.apk" to apkBytes("x"),
        ))

        val names = BundleReader.read(file).parts.map { it.sessionName }

        assertEquals(2, names.size)
        assertEquals("names must stay unique", names.size, names.toSet().size)
        assertEquals("base", names.first())
    }

    @Test
    fun `a bundle named apk is still read as a bundle`() {
        // The server signs by content too; a mislabelled artifact must not fail.
        val file = zip("mislabelled.apk", mapOf(
            "com.whatsapp.apk" to apkBytes("base"),
            "config.arm64_v8a.apk" to apkBytes("abi"),
        ))

        assertTrue(BundleReader.read(file).isBundle)
        assertEquals(2, BundleReader.read(file).parts.size)
    }

    @Test
    fun `a single apk named xapk is still read as a single apk`() {
        val file = zip("mislabelled.xapk", mapOf("AndroidManifest.xml" to "manifest".toByteArray()))

        assertFalse(BundleReader.read(file).isBundle)
        assertEquals(1, BundleReader.read(file).parts.size)
    }

    @Test
    fun `a bundle without a manifest json still installs`() {
        val file = zip("apkm-style.zip", mapOf(
            "base.apk" to apkBytes("base"),
            "split_config.arm64_v8a.apk" to apkBytes("abi"),
        ))

        val contents = BundleReader.read(file)

        assertTrue(contents.isBundle)
        assertEquals(2, contents.parts.size)
        assertNull(contents.packageName)
    }

    @Test
    fun `a dot-only entry name cannot become a traversal token`() {
        val file = zip("odd.xapk", mapOf(
            "com.app.apk" to apkBytes("the base apk, by far the largest entry here"),
            "...apk" to apkBytes("x"),
        ))

        val names = BundleReader.read(file).parts.map { it.sessionName }

        assertTrue("a session name must never be a traversal token", names.none { it.contains("..") })
        assertEquals("names must stay unique", names.size, names.toSet().size)
    }

    @Test
    fun `a session name never starts with a hyphen`() {
        // `pm install-write -S <size> <session> <name> -` takes the name as a bare
        // argument, so a name like "-r" is parsed as an OPTION and the write either
        // fails or writes the wrong thing. The zip entry name is server-supplied.
        val file = zip("hostile.xapk", mapOf(
            "com.app.apk" to apkBytes("the base apk, by far the largest entry here"),
            "-r.apk" to apkBytes("y"),
            "--.apk" to apkBytes("z"),
        ))

        val names = BundleReader.read(file).parts.map { it.sessionName }

        assertEquals(3, names.size)
        assertTrue("no session name may begin with a hyphen", names.none { it.startsWith("-") })
        assertTrue("a hyphen-only name still needs a name", names.contains("split"))
        assertEquals("names must stay unique", names.size, names.toSet().size)
    }

    @Test
    fun `a single apk with an obb-looking asset path is not rejected`() {
        // The expansion-file rule is about a BUNDLE shipping OBBs. A plain APK is
        // itself a zip, and one carrying "assets/.../obb/..." inside is not the
        // same thing - rejecting it would refuse a perfectly installable build.
        val file = zip("plain.apk", mapOf(
            "AndroidManifest.xml" to "manifest".toByteArray(),
            "assets/data/obb/readme.txt" to ByteArray(8),
        ))

        val contents = BundleReader.read(file)

        assertFalse(contents.isBundle)
        assertEquals(1, contents.parts.size)
    }

    @Test
    fun `a single apk carrying a nested apk is not mistaken for a bundle`() {
        // Real apps ship helper APKs under assets/. Matching ".apk" at any depth -
        // the rule the server uses - reads this as a bundle and would install the
        // nested payload as a split, corrupting the result.
        val file = zip("with-nested.apk", mapOf(
            "AndroidManifest.xml" to "manifest".toByteArray(),
            "assets/helper.apk" to apkBytes("a helper shipped inside a normal apk"),
        ))

        val contents = BundleReader.read(file)

        assertFalse(contents.isBundle)
        assertEquals(1, contents.parts.size)
        assertNull("a single apk streams the file, not an entry", contents.parts[0].zipEntry)
    }

    @Test
    fun `a bundle carrying obb files is rejected`() {
        val file = zip("with-obb.xapk", mapOf(
            "com.game.apk" to apkBytes("base"),
            "Android/obb/com.game/main.1.com.game.obb" to ByteArray(64),
        ))

        val error = runCatching { BundleReader.read(file) }.exceptionOrNull()

        assertTrue(error is BundleException)
        assertTrue(error!!.message!!.contains("expansion", ignoreCase = true))
    }

    @Test
    fun `a file that is not a zip is rejected`() {
        val file = folder.newFile("garbage.apk")
        file.writeText("this is not a zip at all")

        assertTrue(runCatching { BundleReader.read(file) }.exceptionOrNull() is BundleException)
    }

    @Test
    fun `a zip that is not an android package is rejected`() {
        val file = zip("photos.zip", mapOf("holiday.jpg" to ByteArray(16)))

        val error = runCatching { BundleReader.read(file) }.exceptionOrNull()

        assertTrue(error is BundleException)
    }

    @Test
    fun `an empty file is rejected`() {
        assertTrue(runCatching { BundleReader.read(folder.newFile("empty.apk")) }
            .exceptionOrNull() is BundleException)
    }

    @Test
    fun `openPart streams a bundle entry and the whole file for a single apk`() {
        val bundle = zip("b.xapk", mapOf("base.apk" to apkBytes("hello")))
        val bundlePart = BundleReader.read(bundle).parts.single()
        val fromEntry = BundleReader.openPart(bundle, bundlePart).use { it.readBytes() }
        assertEquals(apkBytes("hello").size, fromEntry.size)

        val single = zip("s.apk", mapOf("AndroidManifest.xml" to "m".toByteArray()))
        val singlePart = BundleReader.read(single).parts.single()
        val whole = BundleReader.openPart(single, singlePart).use { it.readBytes() }
        assertEquals(single.length().toInt(), whole.size)
    }
}
