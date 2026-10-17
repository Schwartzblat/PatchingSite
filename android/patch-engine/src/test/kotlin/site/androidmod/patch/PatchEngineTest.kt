package site.androidmod.patch

import com.android.apksig.ApkVerifier
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PatchEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ec = ecKeyPair()
    private val copySigner = ApkSigner { unsigned, signed -> unsigned.copyTo(signed, overwrite = true) }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun signedOfficial(key: TestKey): File {
        val unsigned = SyntheticApks.app(tmp.newFile("unsigned-app.apk"))
        val signed = tmp.newFile("official.apk")
        com.android.apksig.ApkSigner.Builder(
            listOf(com.android.apksig.ApkSigner.SignerConfig.Builder("t", key.privateKey, listOf(key.cert)).build()),
        ).setInputApk(unsigned).setOutputApk(signed).setMinSdkVersion(33).setV1SigningEnabled(false).build().sign()
        return signed
    }

    private fun additions(): ByteArray {
        val am = com.reandroid.arsc.chunk.xml.AndroidManifestBlock.empty()
        am.packageName = "mod"
        am.getOrCreateApplicationElement()
        am.manifestElement.newElement("uses-permission").getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString("android.permission.INTERNET")
        am.refreshFull()
        return am.bytes
    }

    private fun bundle(pkg: String, certSha: String): PatchBundle {
        val features = """{"required_keys":["A"],"official_cert_sha256":["$certSha"],"features":[""" +
            """{"id":"f1","title":"Feature One","keys":["A"]},{"id":"f2","title":"Hidden","keys":["B"],"visible":false}]}"""
        val files = mapOf(
            "module.apk" to SyntheticApks.moduleA(tmp.newFile()).readBytes(),
            "paywall.apk" to SyntheticApks.moduleB(tmp.newFile()).readBytes(),
            "manifest-additions.xml" to additions(),
            "features.json" to features.toByteArray(),
        )
        val meta = mapOf(
            "format" to 1, "package" to pkg, "patcher_version" to "3.0.0", "min_sdk" to 30,
            "min_store_version_code" to 5, "dex_order" to listOf("module.apk", "paywall.apk"),
        )
        return PatchBundle.open(bundleZip(tmp.newFile(), files, meta, ec.private), ec.public)
    }

    private fun record(
        pkg: String,
        dex: String,
        supported: Boolean = true,
        versionCode: Long = 1,
        artifacts: String = """{"A":"a","ZED":"z\"q","ALPHA":"a"}""",
        featuresOff: String = """["f1","f2"]""",
    ) = VersionRecord.parse(
        """{"package":"$pkg","version_name":"1","version_code":$versionCode,"apk_sha256":"00","dex_sha256":"$dex",""" +
            """"patcher_version":"3.0.0","artifacts":$artifacts,"missing_keys":[],""" +
            """"features_off":$featuresOff,"supported":$supported,"source":"x"}""",
    )

    private class Setup(val official: File, val bundle: PatchBundle, val record: VersionRecord)

    private fun setup(
        signer: TestKey = TestKeys.generate(),
        pkg: String = "com.example.app.orig",
        bundlePkg: String = pkg,
        dexOverride: String? = null,
        supported: Boolean = true,
        versionCode: Long = 1,
        artifacts: String = """{"A":"a","ZED":"z\"q","ALPHA":"a"}""",
        featuresOff: String = """["f1","f2"]""",
    ): Setup {
        val official = signedOfficial(signer)
        val certSha = sha(signer.cert.encoded)
        return Setup(
            official,
            bundle(bundlePkg, certSha),
            record(pkg, dexOverride ?: DexHash.dexSha256(official), supported, versionCode, artifacts, featuresOff),
        )
    }

    private fun request(
        s: Setup,
        signer: ApkSigner = copySigner,
        work: File = File(tmp.root, "work"),
    ): Triple<PatchEngine, PatchRequest, File> {
        val out = File(tmp.root, "out.apk")
        return Triple(PatchEngine(signer), PatchRequest(s.official, s.bundle, s.record, "tok\"en", 7, work, out), work)
    }

    private fun read(f: File, name: String): ByteArray? =
        ZipFile.builder().setFile(f).get().use { z -> z.getEntry(name)?.let { z.getInputStream(it).readBytes() } }

    private inline fun <reified E : PatchException> refused(s: Setup) {
        val (engine, req, work) = request(s)
        try {
            engine.patch(req)
            fail("expected ${E::class.simpleName}")
        } catch (e: PatchException) {
            assertTrue("got $e", e is E)
        }
        assertFalse(req.output.exists())
        assertFalse(File(work, "unsigned.apk").exists())
    }

    @Test fun patchesSyntheticApp() {
        val s = setup()
        val (engine, req, work) = request(s)
        val result = engine.patch(req)
        assertEquals(req.output, result.output)
        // bundle-derived (f2: key B absent) first, then record-only (f1)
        assertEquals(listOf("f2", "f1"), result.featuresOff)
        assertEquals(listOf("Feature One"), result.visibleFeaturesOff)
        assertFalse(File(work, "unsigned.apk").exists())

        assertEquals("""{"A":"a","ALPHA":"a","ZED":"z\"q"}""", String(read(req.output, "assets/patcher/artifactory.json")!!))
        assertEquals("""{"token":"tok\"en"}""", String(read(req.output, "assets/paywall.json")!!))
        assertEquals(
            """{"patcher_version":"3.0.0","revision":7,"dex_sha256":"${s.record.dexSha256}","features_off":["f2","f1"]}""",
            String(read(req.output, "assets/patcher/patch-info.json")!!),
        )
        assertArrayEquals(SyntheticApks.dexApp1, read(req.output, "classes.dex"))
        assertArrayEquals(SyntheticApks.dexA, read(req.output, "classes3.dex"))
        assertArrayEquals(SyntheticApks.dexB, read(req.output, "classes4.dex"))
        val merged = read(req.output, "AndroidManifest.xml")!!
        assertEquals("com.example.app.orig", ManifestPatcher.packageName(merged))
        assertFalse(merged.contentEquals(read(s.official, "AndroidManifest.xml")))
    }

    @Test fun requestNeverPrintsThePaywallToken() {
        val (_, req, _) = request(setup())
        val printed = req.toString()
        assertFalse("the paywall token is a secret: $printed", "tok\"en" in printed)
        assertTrue(printed.startsWith("PatchRequest("))
        assertTrue("the revision still shows", "revision=7" in printed)
    }

    @Test fun unsupportedRecordRefused() = refused<PatchException.Unsupported>(setup(supported = false))

    @Test fun requiredKeyMissingRefused() =
        refused<PatchException.Unsupported>(setup(artifacts = """{"ZED":"z","B":"b"}"""))

    @Test fun requiredKeyEmptyRefused() =
        refused<PatchException.Unsupported>(setup(artifacts = """{"A":"","B":"b"}"""))

    @Test fun versionCodeMismatchRefused() = refused<PatchException.RecordMismatch>(setup(versionCode = 2))

    @Test fun featuresOffDerivedFromBundle() {
        val s = setup(artifacts = """{"A":"a"}""", featuresOff = "[]")
        val (engine, req, _) = request(s)
        val result = engine.patch(req)
        assertEquals(listOf("f2"), result.featuresOff)
        assertEquals(emptyList<String>(), result.visibleFeaturesOff)
    }

    @Test fun featuresOffFunctionListsDerivedThenRecordOnly() {
        val s = setup(artifacts = """{"A":"a"}""", featuresOff = """["f2","zz"]""")
        assertEquals(listOf("f2", "zz"), featuresOff(s.bundle.features, s.record))
    }

    @Test fun emptyFeatureKeyAndRecordOnlyIdsUnion() {
        val s = setup(artifacts = """{"A":"a","B":""}""", featuresOff = """["zz"]""")
        val (engine, req, _) = request(s)
        assertEquals(listOf("f2", "zz"), engine.patch(req).featuresOff)
    }

    @Test fun unwritableWorkDirIsStorage() {
        val blocker = tmp.newFile("blocker")
        val (engine, req, _) = request(setup(), work = File(blocker, "work"))
        try {
            engine.patch(req)
            fail("expected Storage")
        } catch (e: PatchException.Storage) {
            assertFalse(req.output.exists())
        }
    }

    @Test fun packageMismatchRefused() = refused<PatchException.RecordMismatch>(setup(bundlePkg = "com.other"))

    @Test fun recordForOtherBuildRefused() =
        refused<PatchException.RecordMismatch>(setup(dexOverride = "00".repeat(32)))

    @Test fun notOfficialRefused() {
        val official = TestKeys.generate("official")
        val s = setup(signer = TestKeys.generate("other"))
        val other = Setup(s.official, bundle("com.example.app.orig", sha(official.cert.encoded)), s.record)
        refused<PatchException.NotOfficial>(other)
    }

    @Test fun signFailureLeavesNoUnsigned() {
        val s = setup()
        val (engine, req, work) = request(s, signer = { _, _ -> throw PatchException.SignFailed("boom") })
        try {
            engine.patch(req)
            fail("expected SignFailed")
        } catch (e: PatchException.SignFailed) {
            assertFalse(File(work, "unsigned.apk").exists())
        }
    }

    @Test fun realWhatsAppEndToEnd() {
        val apk = fixtureFile("com.whatsapp-2.26.40.72.apk")
        val bundleZip = fixtureFile(".patcher-work/bundle-com.whatsapp-3.0.0.zip")
        val recordFile = fixtureFile(".patcher-work/wa-record.json")
        val keyFile = System.getProperty("patch.bundleKey")?.let(::File)?.takeIf { it.isFile }
        val signerBin = System.getProperty("patch.fastsigner")?.let(::File)?.takeIf { it.canExecute() }
        assumeTrue("fixtures absent", apk != null && bundleZip != null && recordFile != null && keyFile != null && signerBin != null)

        val dir = Files.createTempDirectory("e2e").toFile()
        try {
            val ks = File(dir, "k.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val p = ProcessBuilder(
                keytool, "-genkeypair", "-alias", "k", "-keyalg", "EC", "-groupname", "secp256r1",
                "-validity", "3650", "-dname", "CN=e2e", "-storetype", "PKCS12",
                "-keystore", ks.path, "-storepass", "secret", "-keypass", "secret",
            ).redirectErrorStream(true).start()
            val kout = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "keytool failed: $kout" }

            val bundle = PatchBundle.open(bundleZip!!, publicKeyFromPem(keyFile!!.readText()))
            val record = VersionRecord.parse(recordFile!!.readText())
            val out = File(dir, "patched.apk")
            val req = PatchRequest(apk!!, bundle, record, "test-token", 1, File(dir, "work"), out)

            val start = System.nanoTime()
            val result = PatchEngine(FastSigner(signerBin!!, ks, "secret".toCharArray())).patch(req)
            val ms = (System.nanoTime() - start) / 1_000_000
            println("realWhatsAppEndToEnd: patch() took $ms ms")

            val v = ApkVerifier.Builder(out).setMinCheckedPlatformVersion(30).build().verify()
            assertTrue("verify errors: ${v.errors}", v.isVerified)
            val art = String(read(out, "assets/patcher/artifactory.json")!!)
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(art) as kotlinx.serialization.json.JsonObject
            assertEquals(record.artifacts, parsed.mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content })
            assertNotNull(parsed["PACKAGE_SIGNATURE"])
            assertEquals("X.Fuz", (parsed["UPGRADE_CHECK_CLASS_NAME"] as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals(parsed.keys.sorted(), parsed.keys.toList())
            assertArrayEquals(read(apk, "classes.dex"), read(out, "classes.dex"))
            assertEquals(record.featuresOff, result.featuresOff)
            ZipFile.builder().setFile(out).get().use { z ->
                for (e in z.entries) {
                    if (e.method != java.util.zip.ZipEntry.STORED) continue
                    val align = if (e.name.startsWith("lib/") && e.name.endsWith(".so")) 16384 else 4
                    assertEquals("${e.name} offset ${e.dataOffset}", 0L, e.dataOffset % align)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
