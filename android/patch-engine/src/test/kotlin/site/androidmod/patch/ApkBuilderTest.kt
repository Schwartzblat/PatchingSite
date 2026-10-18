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

    @Test fun libTargetPutsOnlyThatAbi() {
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val module = zip(
            File(dir, "hooks.apk"),
            listOf("classes.dex" to SyntheticApks.dexA) +
                abis.mapIndexed { i, abi -> "lib/$abi/libarthooks.so" to ByteArray(16) { (i + 10).toByte() } },
        ).readBytes()
        val o = File(dir, "abi-out.apk")
        val added = ApkBuilder.build(
            set.app, set.inputs.copy(modules = listOf(module)), o,
            BuildOptions(libs = LibTarget("armeabi-v7a", "armeabi")),
        )
        assertEquals(listOf("lib/armeabi/libarthooks.so"), added.filter { it.startsWith("lib/") })
        assertEquals(listOf("lib/armeabi/libarthooks.so", "lib/x86_64/libapp.so"), names(o).filter { it.startsWith("lib/") }.sorted())
        assertArrayEquals(ByteArray(16) { 11 }, read(o, "lib/armeabi/libarthooks.so"))
        assertEquals(ZipEntry.STORED, open(o) { it.getEntry("lib/armeabi/libarthooks.so").method })
    }

    @Test fun dropLeavesEntriesOutAndRenumbersDex() {
        val o = File(dir, "drop-out.apk")
        val added = ApkBuilder.build(set.app, set.inputs, o, BuildOptions(drop = setOf("classes2.dex", "META-INF/services/a.B")))
        val n = names(o)
        assertFalse("META-INF/services/a.B" in n)
        assertArrayEquals(SyntheticApks.dexApp1, read(o, "classes.dex"))
        assertArrayEquals(SyntheticApks.dexA, read(o, "classes2.dex"))
        assertArrayEquals(SyntheticApks.dexB, read(o, "classes3.dex"))
        assertFalse("classes4.dex" in n)
        assertEquals(listOf("classes2.dex", "classes3.dex"), added.filter { it.endsWith(".dex") })
        assertEquals(n.size, n.toSet().size)
    }

    @Test fun refuseCollisionsThrows() {
        val o = File(dir, "collide.apk")
        try {
            ApkBuilder.build(set.app, set.inputs, o, BuildOptions(refuseCollisions = true))
            fail("expected Unsupported")
        } catch (e: PatchException.Unsupported) {
            assertEquals("com.example.app already has assets/x.txt", e.message)
        }
        assertFalse(o.exists())
        assertFalse(dir.list()!!.any { it.endsWith(".tmp") })
        // A dropped entry no longer collides, and the replaced manifest never counts.
        ApkBuilder.build(set.app, set.inputs, o, BuildOptions(drop = setOf("assets/x.txt"), refuseCollisions = true))
        assertArrayEquals(SyntheticApks.moduleAsset, read(o, "assets/x.txt"))
    }

    @Test fun describeSeesEveryAddedName() {
        val o = File(dir, "described.apk")
        val seen = mutableListOf<List<String>>()
        val added = ApkBuilder.build(
            set.app, set.inputs, o,
            BuildOptions(describe = { names -> seen += names; "assets/patcher/info.json" to names.joinToString("\n").toByteArray() }),
        )
        assertEquals(1, seen.size)
        val expected = listOf(
            "assets/patcher/artifactory.json", "assets/patcher/icon.png", "assets/patcher/info.json",
            "assets/stitch/com.smali_generator.apk", "assets/x.txt", "classes3.dex", "classes4.dex", "lib/x86_64/libarthooks.so",
        )
        assertEquals(expected, added)
        assertEquals(expected - "assets/patcher/info.json", seen.single())
        assertEquals(seen.single().joinToString("\n"), String(read(o, "assets/patcher/info.json")!!))
        assertEquals("written last", "assets/patcher/info.json", names(o).last())
        // Without describe, the build still returns what it added, the manifest aside.
        assertEquals(expected - "assets/patcher/info.json", ApkBuilder.build(set.app, set.inputs, File(dir, "plain.apk")))
    }

    @Test fun stripV1KeepsEverythingElseRaw() {
        val signed = zip(
            File(dir, "signed.apk"),
            listOf(
                "AndroidManifest.xml" to SyntheticApks.appManifest,
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0".toByteArray(),
                "META-INF/DEV.SF" to ByteArray(4), "META-INF/DEV.RSA" to ByteArray(4),
                "META-INF/K.EC" to ByteArray(4), "META-INF/K.DSA" to ByteArray(4),
                "META-INF/sub/X.SF" to ByteArray(4), "META-INF/services/a.B" to "svc".toByteArray(),
                "classes.dex" to SyntheticApks.dexApp1,
            ),
        )
        // set.app mixes stored and deflated entries.
        for (src in listOf(signed, set.app)) {
            val o = File(dir, "stripped-${src.name}")
            ApkBuilder.stripV1(src, o)
            open(src) { s ->
                open(o) { z ->
                    val kept = s.entries.asSequence().filter {
                        it.name !in setOf("META-INF/MANIFEST.MF", "META-INF/DEV.SF", "META-INF/DEV.RSA", "META-INF/K.EC", "META-INF/K.DSA", "META-INF/CERT.RSA")
                    }.toList()
                    assertEquals(kept.map { it.name }, z.entries.asSequence().map { it.name }.toList())
                    for (e in kept) {
                        val oe = z.getEntry(e.name)
                        assertEquals(e.name, e.method, oe.method)
                        assertEquals(e.name, e.crc, oe.crc)
                        assertArrayEquals(e.name, s.getRawInputStream(e).readBytes(), z.getRawInputStream(oe).readBytes())
                    }
                }
            }
        }
        assertTrue("META-INF/sub/X.SF" in names(File(dir, "stripped-signed.apk")))
        assertTrue(names(File(dir, "stripped-app.apk")).contains("lib/x86_64/libapp.so"))
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
