package site.androidmod.patch

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

class PatchBundleTest {
    private val key = ecKeyPair()
    private val featuresJson = """{"required_keys":["A"],"official_cert_sha256":["ff"],"features":[{"id":"f","title":"F","keys":["A"]}]}"""
    private val files = mapOf(
        "module.apk" to "module".toByteArray(), "paywall.apk" to "paywall".toByteArray(),
        "manifest-additions.xml" to "xml".toByteArray(), "features.json" to featuresJson.toByteArray(),
    )
    private val meta = mapOf("format" to 1, "package" to "com.x", "patcher_version" to "1.2", "min_sdk" to 30,
        "min_store_version_code" to 5, "dex_order" to listOf("module.apk", "paywall.apk"))

    private fun tmp() = File.createTempFile("bundle", ".zip").also { it.deleteOnExit() }
    private fun bundle(files: Map<String, ByteArray> = this.files, meta: Map<String, Any?> = this.meta,
                        extra: List<Pair<String, ByteArray>> = emptyList()) = bundleZip(tmp(), files, meta, key.private, extra)

    @Test fun oversizedEntryRejected() {
        val big = ByteArray((PatchBundle.MAX_ENTRY_BYTES + 1).toInt())
        try {
            PatchBundle.open(bundle(files = files + ("big.bin" to big)), key.public)
            org.junit.Assert.fail("expected BadBundle")
        } catch (e: PatchException.BadBundle) {
            org.junit.Assert.assertTrue(e.message, e.message!!.contains("big.bin"))
        }
    }

    @Test fun oversizedTotalRejected() {
        val chunk = ByteArray((PatchBundle.MAX_ENTRY_BYTES - 1).toInt())
        try {
            PatchBundle.open(bundle(files = files + mapOf("a.bin" to chunk, "b.bin" to chunk, "c.bin" to chunk)), key.public)
            org.junit.Assert.fail("expected BadBundle")
        } catch (e: PatchException.BadBundle) {
            org.junit.Assert.assertTrue(e.message, e.message!!.contains("64 MiB"))
        }
    }

    @Test fun validBundleOpens() {
        val b = PatchBundle.open(bundle(), key.public)
        assertEquals(BundleMeta(1, "com.x", "1.2", 30, 5L, listOf("module.apk", "paywall.apk"), b.meta.files), b.meta)
        assertEquals(files.keys, b.meta.files.keys)
        assertArrayEquals("module".toByteArray(), b.bytes("module.apk"))
        assertEquals(listOf("A"), b.features!!.requiredKeys)
        assertFalse(b.isGeneric)
        assertNull(b.catalog)
    }
    @Test(expected = PatchException.BadBundle::class) fun wrongKeyRejected() { PatchBundle.open(bundle(), ecKeyPair().public) }
    @Test(expected = PatchException.BadBundle::class) fun tamperedFileRejected() {
        val good = bundle()
        val entries = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(good).get().use { z ->
            z.entries.toList().map { it.name to z.getInputStream(it).readBytes() }
        }.map { if (it.first == "module.apk") it.first to "evil!!".toByteArray() else it }
        PatchBundle.open(zip(tmp(), entries), key.public)
    }
    @Test(expected = PatchException.BadBundle::class) fun unlistedFileRejected() { PatchBundle.open(bundle(extra = listOf("evil.apk" to byteArrayOf(1))), key.public) }
    @Test(expected = PatchException.BadBundle::class) fun missingListedFileRejected() {
        val good = bundle()
        val entries = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(good).get().use { z ->
            z.entries.toList().map { it.name to z.getInputStream(it).readBytes() }
        }.filter { it.first != "paywall.apk" }
        PatchBundle.open(zip(tmp(), entries), key.public)
    }
    @Test(expected = PatchException.BadBundle::class) fun duplicateEntryRejected() {
        PatchBundle.open(bundle(extra = listOf("features.json" to featuresJson.toByteArray())), key.public)
    }
    @Test(expected = PatchException.BadBundle::class) fun unknownFormatRejected() { PatchBundle.open(bundle(meta = meta + ("format" to 2)), key.public) }

    private fun catalogJson(format: Int = 1) =
        """{"format":$format,"sdks":[{"name":"S","packages":["com/s/"],"hosts":["s.example"]}],"protected_hosts":["p.example"],""" +
            """"url_rules":[["h.example","/p"]],"app_frame_packages":["com/app/"],"harvest_bare_tlds":["com"],"package_roots":["com"]}"""
    private val genericMeta = meta + ("package" to "generic")
    private val genericFiles = mapOf(
        "module.apk" to "module".toByteArray(), "paywall.apk" to "paywall".toByteArray(),
        "manifest-additions.xml" to "xml".toByteArray(), "catalog.json" to catalogJson().toByteArray(),
    )

    private fun rejects(expect: String, open: () -> PatchBundle) {
        try {
            open()
            fail("expected BadBundle")
        } catch (e: PatchException.BadBundle) {
            assertTrue(e.message, e.message!!.contains(expect))
        }
    }

    @Test fun genericBundleOpensWithoutFeatures() {
        val zip = bundle(files = genericFiles, meta = genericMeta)
        val b = PatchBundle.open(zip, key.public)
        assertNull(b.features)
        assertEquals(1, b.catalog!!.format)
        assertEquals("S", b.catalog!!.sdks.single().name)
        assertEquals(listOf(listOf("h.example", "/p")), b.catalog!!.urlRules)
        assertTrue(b.isGeneric)
        val metaBytes = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(zip).get().use { z ->
            z.getInputStream(z.getEntry("bundle.json")).readBytes()
        }
        val hex = java.security.MessageDigest.getInstance("SHA-256").digest(metaBytes).joinToString("") { "%02x".format(it) }
        assertEquals(hex, b.id)
    }

    @Test fun genericBundleWithFeaturesRejected() {
        rejects("features.json") { PatchBundle.open(bundle(files = genericFiles + ("features.json" to featuresJson.toByteArray()), meta = genericMeta), key.public) }
    }

    @Test fun appBundleWithoutFeaturesRejected() {
        rejects("features.json") { PatchBundle.open(bundle(files = files - "features.json"), key.public) }
    }

    @Test fun genericBundleWithoutCatalogRejected() {
        rejects("catalog.json") { PatchBundle.open(bundle(files = genericFiles - "catalog.json", meta = genericMeta), key.public) }
    }

    @Test fun badUrlRuleRejected() {
        val bad = catalogJson().replace("""["h.example","/p"]""", """["h.example"]""")
        rejects("url_rules") { PatchBundle.open(bundle(files = genericFiles + ("catalog.json" to bad.toByteArray()), meta = genericMeta), key.public) }
    }

    @Test fun unknownCatalogFormatRejected() {
        rejects("catalog format 2") {
            PatchBundle.open(bundle(files = genericFiles + ("catalog.json" to catalogJson(2).toByteArray()), meta = genericMeta), key.public)
        }
    }

    @Test fun badEntryNameRejected() {
        for (name in listOf("a/b", "a\\b", "..x")) {
            rejects("bad entry name") { PatchBundle.open(bundle(files = files + (name to byteArrayOf(1))), key.public) }
        }
    }

    @Test fun dexOrderNameMustBeListed() {
        rejects("nope.apk") { PatchBundle.open(bundle(meta = meta + ("dex_order" to listOf("module.apk", "nope.apk"))), key.public) }
    }

    @Test fun realBundleOpens() {
        val zip = fixtureFile(".patcher-work/bundle-com.whatsapp-3.0.0.zip"); assumeTrue(zip != null)
        val keyFile = File(System.getProperty("patch.bundleKey")); assumeTrue(keyFile.isFile)
        val b = PatchBundle.open(zip!!, publicKeyFromPem(keyFile.readText()))
        assertEquals("com.whatsapp", b.meta.packageName)
        assertEquals(33, b.meta.minSdk)
        assertEquals(listOf("module.apk", "paywall.apk"), b.meta.dexOrder)
        assertEquals(listOf("PACKAGE_SIGNATURE", "FMESSAGE_CLASS", "SETTINGS_ACTIVITY_CLASS_NAME"), b.features!!.requiredKeys)
    }
}
