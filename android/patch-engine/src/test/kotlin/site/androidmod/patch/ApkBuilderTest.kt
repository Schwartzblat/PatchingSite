package site.androidmod.patch

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ApkBuilderTest {
    private lateinit var dir: File
    private lateinit var set: SyntheticApks.Set
    private lateinit var out: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("apkb").toFile()
        set = SyntheticApks.create(dir)
        out = File(dir, "out.apk")
        ApkBuilder.build(set.app, set.inputs, out)
    }

    private fun <T> open(f: File, block: (ZipFile) -> T): T = ZipFile.builder().setFile(f).get().use(block)
    private fun read(f: File, name: String): ByteArray? =
        open(f) { z -> z.getEntry(name)?.let { z.getInputStream(it).readBytes() } }
    private fun names(f: File) = open(f) { z -> z.entries.asSequence().map { it.name }.toList() }

    @Test fun copiesAppEntriesRaw() {
        open(set.app) { a ->
            open(out) { o ->
                var n = 0
                for (e in a.entries) {
                    if (e.name == "AndroidManifest.xml" || e.name == "assets/x.txt" || e.name.startsWith("META-INF/") && !e.name.contains("services")) continue
                    val oe = o.getEntry(e.name)
                    assertNotNull(e.name, oe)
                    assertEquals(e.method, oe.method)
                    assertArrayEquals(e.name, a.getRawInputStream(e).readBytes(), o.getRawInputStream(oe).readBytes())
                    n++
                }
                assertEquals(4, n)
            }
        }
    }

    @Test fun newManifestIsWritten() {
        assertArrayEquals(set.inputs.manifest, read(out, "AndroidManifest.xml"))
        assertEquals(1, names(out).count { it == "AndroidManifest.xml" })
    }

    @Test fun dropsV1SignatureButKeepsServices() {
        val n = names(out)
        assertFalse("META-INF/MANIFEST.MF" in n)
        assertFalse("META-INF/CERT.RSA" in n)
        assertTrue("META-INF/services/a.B" in n)
    }

    @Test fun appendsModuleDexInOrder() {
        assertArrayEquals(SyntheticApks.dexA, read(out, "classes3.dex"))
        assertArrayEquals(SyntheticApks.dexB, read(out, "classes4.dex"))
        assertArrayEquals(SyntheticApks.dexApp1, read(out, "classes.dex"))
    }

    @Test fun addsLibsOnlyForAppAbis() {
        open(out) { z ->
            assertEquals(ZipEntry.STORED, z.getEntry("lib/x86_64/libarthooks.so").method)
            assertEquals(ZipEntry.STORED, z.getEntry("lib/x86_64/libapp.so").method)
        }
        assertTrue(names(out).none { it.startsWith("lib/arm64-v8a/") })
    }

    @Test fun addsAllModuleAbisWhenAppHasNoLib() {
        val app = zip(File(dir, "nolib.apk"), listOf("classes.dex" to SyntheticApks.dexApp1))
        val o = File(dir, "nolib-out.apk")
        ApkBuilder.build(app, set.inputs, o)
        val n = names(o)
        assertTrue("lib/arm64-v8a/libarthooks.so" in n)
        assertTrue("lib/x86_64/libarthooks.so" in n)
        assertArrayEquals(SyntheticApks.dexA, read(o, "classes2.dex"))
    }

    @Test fun moduleAssetsAndResourceApk() {
        assertNotNull(read(out, "assets/patcher/icon.png"))
        assertArrayEquals(SyntheticApks.moduleAsset, read(out, "assets/x.txt"))
        assertEquals(1, names(out).count { it == "assets/x.txt" })
        val res = File(dir, "res.apk").also { it.writeBytes(read(out, "assets/stitch/com.smali_generator.apk")!!) }
        assertArrayEquals(SyntheticApks.arsc, read(res, "resources.arsc"))
        assertNotNull(read(res, "AndroidManifest.xml"))
        assertNotNull(read(res, "res/layout/a.xml"))
        assertNull(read(res, "classes.dex"))
        assertEquals(ZipEntry.STORED, open(out) { it.getEntry("assets/stitch/com.smali_generator.apk").method })
    }

    @Test fun jsonAssetsWritten() {
        assertArrayEquals(set.inputs.jsonAssets.getValue("assets/patcher/artifactory.json"), read(out, "assets/patcher/artifactory.json"))
    }

    @Test fun outputHasNoDuplicateNamesAndNoTempFiles() {
        val n = names(out)
        assertEquals(n.size, n.toSet().size)
        assertEquals(listOf("a.apk", "app.apk", "b.apk", "nolib.apk").filter { File(dir, it).exists() }.sorted(),
            dir.list()!!.filter { it.endsWith(".apk") && it != "out.apk" && it != "res.apk" }.sorted())
        assertFalse(dir.list()!!.any { it.endsWith(".tmp") })
    }

    @Test fun failureLeavesNoOutput() {
        val o = File(dir, "never.apk")
        try {
            ApkBuilder.build(File(dir, "missing.apk"), set.inputs, o)
            fail("expected exception")
        } catch (e: PatchException) {
            // expected
        }
        assertFalse(o.exists())
        assertFalse(dir.list()!!.any { it.endsWith(".tmp") })
    }
}
