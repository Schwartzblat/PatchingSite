package site.androidmod.patch

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The phone's scan against the desktop finder: each `generic/goldens/<name>.json` is golden.py's output
 * for `<name>.apk` or `<name>.xapk` in the corpus (`patch.genericCorpus`); a case skips when its app is absent.
 */
@RunWith(Parameterized::class)
class GoldenScanTest(private val name: String) {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun scanEqualsDesktopFinder() {
        val corpus = System.getProperty("patch.genericCorpus")
        val app = corpus?.let { dir -> listOf("apk", "xapk").map { File(dir, "$name.$it") }.firstOrNull { it.isFile } }
        assumeTrue("$name absent from the corpus", app != null)
        val golden = Json.decodeFromString(
            MapSerializer(String.serializer(), String.serializer()),
            javaClass.getResource("/generic/goldens/$name.json")!!.readText(),
        )
        val apks = if (app!!.name.endsWith(".xapk")) unpackXapk(app) else listOf(app)
        val phone = AdScan.scan(apks, genericCatalog()).artifacts
        assertEquals(golden.keys, phone.keys)
        for ((key, desktop) in golden) {
            val got = phone.getValue(key)
            if (got == desktop) continue
            val (p, d) = items(got) to items(desktop)
            throw AssertionError("$key differs for $name: only on the phone ${p - d}, only on the desktop ${d - p}" +
                "\n phone:   $got\n desktop: $desktop")
        }
    }

    /** A list key's items: `AD_SDKS_PRESENT` is joined by ", ", the rest by ",". */
    private fun items(value: String): Set<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** The xapk's top-level `*.apk` entries, unpacked; the base (its manifest has no `split`) first. */
    private fun unpackXapk(xapk: File): List<File> {
        val dir = tmp.newFolder("xapk")
        val apks = ZipFile(xapk).use { zip ->
            zip.entries().toList().filter { !it.isDirectory && '/' !in it.name && it.name.endsWith(".apk") }.map { e ->
                File(dir, e.name).also { out -> zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } } }
            }
        }
        val (bases, splits) = apks.partition { isBase(it) }
        assertEquals("one base in ${xapk.name}", 1, bases.size)
        return bases + splits.sortedBy { it.name }
    }

    private fun isBase(apk: File): Boolean = ZipFile(apk).use { zip ->
        zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { AndroidManifestBlock.load(it).split == null }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun goldens(): List<String> =
            File(GoldenScanTest::class.java.getResource("/generic/goldens")!!.toURI())
                .listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith("-iap.json") }!!
                .map { it.name.removeSuffix(".json") }
                .sorted()
    }
}
