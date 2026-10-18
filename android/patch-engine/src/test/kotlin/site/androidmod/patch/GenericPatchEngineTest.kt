package site.androidmod.patch

import com.android.apksig.ApkVerifier
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GenericPatchEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private companion object {
        val user by lazy { TestKeys.generate("user") }
        val dev by lazy { TestKeys.generate("developer") }
        val other by lazy { TestKeys.generate("other") }
        const val TOKEN = "tok\"en-secret-1"
        const val PKG = "com.example.app"
        val V1 = Regex("META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))")
        val HOOK_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        fun hook(abi: String) = ByteArray(32) { (HOOK_ABIS.indexOf(abi) + 20).toByte() }
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }

    private val ec = ecKeyPair()
    private val work by lazy { File(tmp.root, "work") }
    private val engine by lazy { GenericPatchEngine(apksigSigner(user)) }

    private val moduleDex = TinyDex.build(emptyList(), listOf(TinyClass("Lcom/smali_generator/InitProviderGeneric;")))
    private val paywallDex = TinyDex.build(emptyList(), listOf(TinyClass("Lcom/paywall/InitProviderPaywallGeneric;")))
    private val appClasses = listOf(
        TinyClass("Lcom/applovin/sdk/AppLovinSdk;", listOf(TinyMethod(TinyDex.constString(0)))),
        TinyClass("Lcom/example/app/Main;"),
    )
    private val appDex2 = TinyDex.build(emptyList(), listOf(TinyClass("Lcom/example/app/Second;")))
    private val baseManifest = manifestWith(
        PKG, versionCode = 5, versionName = "1.0", targetSdk = 34, components = listOf("activity" to "$PKG.Main"),
    )

    private fun resource(name: String) = GenericPatchEngineTest::class.java.getResource(name)!!.readBytes()

    private fun moduleApk(): ByteArray = zip(
        tmp.newFile(),
        listOf("AndroidManifest.xml" to SyntheticApks.manifest("com.smali_generator"), "classes.dex" to moduleDex) +
            HOOK_ABIS.map { "lib/$it/libarthooks.so" to hook(it) } +
            listOf("resources.arsc" to ByteArray(40) { 6 }),
    ).readBytes()

    private fun paywallApk(): ByteArray = zip(
        tmp.newFile(),
        listOf(
            "AndroidManifest.xml" to SyntheticApks.manifest("com.paywall"),
            "classes.dex" to paywallDex,
            "resources.arsc" to ByteArray(56) { 8 },
        ),
    ).readBytes()

    private fun bundle(
        patcherVersion: String = "1.0.0",
        module: ByteArray = moduleApk(),
        paywall: ByteArray = paywallApk(),
        pkg: String = PatchBundle.GENERIC_PACKAGE,
    ): PatchBundle {
        val kindFile = if (pkg == PatchBundle.GENERIC_PACKAGE) {
            "catalog.json" to resource("/generic/catalog.json")
        } else {
            "features.json" to """{"required_keys":[],"official_cert_sha256":["00"],"features":[]}""".toByteArray()
        }
        val files = mapOf(
            "module.apk" to module,
            "paywall.apk" to paywall,
            "manifest-additions.xml" to resource("/generic/manifest-additions.xml"),
            kindFile,
        )
        val meta = mapOf(
            "format" to 1, "package" to pkg, "patcher_version" to patcherVersion, "min_sdk" to 33,
            "min_store_version_code" to 0, "dex_order" to listOf("module.apk", "paywall.apk"),
        )
        return PatchBundle.open(bundleZip(tmp.newFile(), files, meta, ec.private), ec.public)
    }

    private val bundle by lazy { bundle() }

    /** An installed-app APK, signed by its developer with v1 (so it carries signature files) as well as v2 and v3. */
    @Suppress("DEPRECATION")
    private fun devSigned(dir: File, name: String, entries: List<Pair<String, ByteArray>>, stored: Boolean = false): File {
        val unsigned = zip(tmp.newFile(), entries, stored)
        val signed = File(dir, name)
        com.android.apksig.ApkSigner.Builder(
            listOf(com.android.apksig.ApkSigner.SignerConfig.Builder("dev", dev.privateKey, listOf(dev.cert)).build()),
        ).setInputApk(unsigned).setOutputApk(signed).setMinSdkVersion(33).setV1SigningEnabled(true).build().sign()
        return signed
    }

    /** The synthetic app: a base with an AppLovin class and no libs, and splits config.arm64_v8a and config.en. */
    private fun app(
        classes: List<TinyClass> = appClasses,
        baseExtra: List<Pair<String, ByteArray>> = emptyList(),
    ): List<File> {
        val dir = tmp.newFolder()
        val base = devSigned(
            dir, "base.apk",
            listOf(
                "AndroidManifest.xml" to baseManifest,
                "classes.dex" to TinyDex.build(listOf("https://ms.applvn.com/x"), classes),
                "classes2.dex" to appDex2,
                "assets/app.txt" to "app asset".toByteArray(),
            ) + baseExtra,
        )
        fun split(name: String) = manifestWith(PKG, versionCode = 5, split = name)
        val abi = devSigned(
            dir, "split_config.arm64_v8a.apk",
            listOf("AndroidManifest.xml" to split("config.arm64_v8a"), "lib/arm64-v8a/libapp.so" to ByteArray(64) { 1 }),
            stored = true,
        )
        val en = devSigned(
            dir, "split_config.en.apk",
            listOf("AndroidManifest.xml" to split("config.en"), "resources.arsc" to ByteArray(32) { 3 }),
            stored = true,
        )
        return listOf(base, abi, en)
    }

    private fun request(
        apks: List<File>,
        out: File = tmp.newFolder(),
        source: GenericSource = GenericSource.ORIGINAL,
        bundle: PatchBundle = this.bundle,
        token: String = TOKEN,
        cert: String = sha(user.cert.encoded),
        sdk: Int = 36,
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
    ) = GenericPatchRequest(apks, source, bundle, token, cert, sdk, abis, work, out)

    private fun <T> open(f: File, block: (ZipFile) -> T): T = ZipFile.builder().setFile(f).get().use(block)
    private fun read(f: File, name: String): ByteArray? =
        open(f) { z -> z.getEntry(name)?.let { z.getInputStream(it).readBytes() } }
    private fun names(f: File) = open(f) { z -> z.entries.asSequence().map { it.name }.toList() }

    private fun authorities(manifest: ByteArray): List<String> =
        AndroidManifestBlock.load(ByteArrayInputStream(manifest)).applicationElement.elements.asSequence()
            .map { it as ResXmlElement }.filter { it.name == "provider" }
            .mapNotNull { p -> p.attributes.asSequence().firstOrNull { it.nameId == 0x01010018 }?.valueAsString }
            .toList()

    private fun assertSignedBy(apk: File, key: TestKey) {
        val r = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(36).build().verify()
        assertTrue("${apk.name}: ${r.errors}", r.isVerified)
        assertEquals(apk.name, listOf(key.cert), r.signerCertificates)
    }

    /** Every entry of [input] but its v1 signature files is in [output], in order, with the same raw bytes. */
    private fun assertSameBesidesSignature(input: File, output: File) = open(input) { i ->
        open(output) { o ->
            val kept = i.entries.asSequence().filter { !V1.matches(it.name) }.toList()
            assertEquals(output.name, kept.map { it.name }, o.entries.asSequence().map { it.name }.toList())
            for (e in kept) {
                val oe = o.getEntry(e.name)
                assertEquals(e.name, e.method, oe.method)
                assertArrayEquals(e.name, i.getRawInputStream(e).readBytes(), o.getRawInputStream(oe).readBytes())
            }
        }
    }

    private fun assertNothingLeft(out: File, vararg allowed: String) {
        assertEquals(allowed.toList(), out.list().orEmpty().sorted())
        assertEquals(emptyList<String>(), work.list().orEmpty().toList())
    }

    private inline fun <reified E : PatchException> refused(request: GenericPatchRequest): E {
        try {
            engine.patch(request)
        } catch (e: PatchException) {
            assertTrue("got $e", e is E)
            assertFalse("the token never reaches a message: ${e.message}", TOKEN in e.message.orEmpty())
            return e as E
        }
        throw AssertionError("expected ${E::class.simpleName}")
    }

    @Test fun patchesBaseAndResignsSplits() {
        val apks = app()
        val out = tmp.newFolder("out")
        // apksig drops an input's v1 files itself, so check what the signer is handed (fastsigner may keep them).
        val handed = mutableListOf<Pair<File, List<String>>>()
        val signer = apksigSigner(user)
        val recording = ApkSigner { unsigned, signed -> handed += unsigned to names(unsigned); signer.sign(unsigned, signed) }
        // The cert comparison ignores case.
        val result = GenericPatchEngine(recording).patch(request(apks, out, cert = sha(user.cert.encoded).uppercase()))

        assertEquals(3, handed.size)
        for ((unsigned, entries) in handed) {
            assertEquals(work, unsigned.parentFile)
            assertEquals(unsigned.name, emptyList<String>(), entries.filter { V1.matches(it) })
        }
        assertEquals(apks.map { it.name }, result.apks.map { it.name })
        assertEquals(apks.map { it.name }.sorted(), out.list()!!.sorted())
        result.apks.forEach { assertEquals(out, it.parentFile); assertSignedBy(it, user) }
        assertEquals(emptyList<String>(), work.list()!!.toList())
        assertEquals(PKG, result.facts.packageName)
        assertEquals(5L, result.facts.versionCode)

        val base = result.apks[0]
        assertArrayEquals(read(apks[0], "classes.dex"), read(base, "classes.dex"))
        assertArrayEquals(appDex2, read(base, "classes2.dex"))
        assertArrayEquals("module dex follows the app's", moduleDex, read(base, "classes3.dex"))
        assertArrayEquals(paywallDex, read(base, "classes4.dex"))
        assertEquals(listOf("lib/arm64-v8a/libarthooks.so"), names(base).filter { it.startsWith("lib/") })
        assertArrayEquals(hook("arm64-v8a"), read(base, "lib/arm64-v8a/libarthooks.so"))
        assertEquals(java.util.zip.ZipEntry.STORED, open(base) { it.getEntry("lib/arm64-v8a/libarthooks.so").method })

        val manifest = read(base, "AndroidManifest.xml")!!
        assertEquals(
            listOf("$PKG.com.paywall.InitProviderPaywallGeneric", "$PKG.com.smali_generator.InitProviderGeneric"),
            authorities(manifest).sorted(),
        )
        assertArrayEquals(baseManifest, read(base, GenericPatchInfo.ORIGINAL_MANIFEST))

        val art = Json.parseToJsonElement(String(read(base, "assets/patcher/artifactory.json")!!)).jsonObject
        assertEquals("AppLovin", art.getValue("AD_SDKS_PRESENT").jsonPrimitive.content)
        assertEquals(art.keys.sorted(), art.keys.toList())
        assertEquals(result.artifacts, art.mapValues { it.value.jsonPrimitive.content })
        assertEquals("""{"token":"tok\"en-secret-1"}""", String(read(base, "assets/paywall.json")!!))

        val info = GenericPatchInfo.read(base)!!
        assertEquals(result.info, info)
        assertEquals("generic", info.kind)
        assertEquals(bundle.id, info.bundleId)
        assertEquals(GenericPatchInfo("generic", "1.0.0", bundle.id, 1, "arm64-v8a", "arm64-v8a", "AppLovin", info.added), info)
        assertEquals(
            listOf(
                "assets/patcher/artifactory.json", "assets/patcher/original-manifest.axml", "assets/patcher/patch-info.json",
                "assets/paywall.json", "assets/stitch/com.paywall.apk", "assets/stitch/com.smali_generator.apk",
                "classes3.dex", "classes4.dex", "lib/arm64-v8a/libarthooks.so",
            ),
            info.added,
        )
        val baseNames = names(base)
        assertEquals(baseNames.size, baseNames.toSet().size)
        assertEquals((names(apks[0]).filter { !V1.matches(it) } + info.added).sorted(), baseNames.sorted())
        assertEquals("patch-info is written last", GenericPatchInfo.ENTRY, baseNames.last())

        for (i in 1 until apks.size) assertSameBesidesSignature(apks[i], result.apks[i])
    }

    @Test fun refreshFromPatchedMatchesOriginal() {
        val first = engine.patch(request(app()))
        val second = engine.patch(request(first.apks, source = GenericSource.PATCHED, token = "new-token"))
        val (b1, b2) = first.apks[0] to second.apks[0]

        assertEquals(names(b1), names(b2))
        assertEquals(4, names(b2).count { DexHash.dexIndex(it) != null })
        assertArrayEquals(read(b1, "AndroidManifest.xml"), read(b2, "AndroidManifest.xml"))
        assertEquals("providers once each", 2, authorities(read(b2, "AndroidManifest.xml")!!).size)
        assertEquals("""{"token":"new-token"}""", String(read(b2, "assets/paywall.json")!!))
        for (name in names(b1) - "assets/paywall.json") assertArrayEquals(name, read(b1, name), read(b2, name))
        assertEquals(first.info, second.info)
        assertEquals(first.facts, second.facts)
        for (i in 1 until first.apks.size) assertSameBesidesSignature(first.apks[i], second.apks[i])
        second.apks.forEach { assertSignedBy(it, user) }
    }

    @Test fun refreshFromSavedOriginal() {
        val apks = app()
        val first = engine.patch(request(apks))
        val newer = bundle(patcherVersion = "1.0.1")
        val second = engine.patch(request(apks, bundle = newer))
        assertNotEquals(bundle.id, newer.id)
        assertEquals(bundle.id, first.info.bundleId)
        assertEquals(newer.id, second.info.bundleId)
        assertEquals(newer.id, GenericPatchInfo.read(second.apks[0])!!.bundleId)
        assertEquals("1.0.1", second.info.patcherVersion)
        assertEquals(first.info.added, second.info.added)
    }

    @Test fun appBundleRefused() {
        val out = tmp.newFolder()
        val e = refused<PatchException.BadBundle>(request(app(), out, bundle = bundle(pkg = PKG)))
        assertTrue(e.message, "generic" in e.message!!)
        assertNothingLeft(out)
    }

    @Test fun unpatchableAppRefusedBeforeAnyOutput() {
        val out = tmp.newFolder()
        val e = refused<PatchException.Unpatchable>(
            request(app(baseExtra = listOf("lib/arm64-v8a/libpairipcore.so" to ByteArray(8))), out),
        )
        assertEquals(listOf(CantPatch.PAIRIP), e.reasons)
        assertNothingLeft(out)
    }

    @Test fun pairipClassesRefused() {
        val out = tmp.newFolder()
        val e = refused<PatchException.Unpatchable>(request(app(classes = appClasses + TinyClass("Lcom/pairip/VMRunner;")), out))
        assertEquals(listOf(CantPatch.PAIRIP), e.reasons)
        assertNothingLeft(out)
    }

    @Test fun alreadyPatchedRefused() {
        val first = engine.patch(request(app()))
        val out = tmp.newFolder()
        val e = refused<PatchException.Unpatchable>(request(first.apks, out))
        assertEquals(listOf(CantPatch.ALREADY_PATCHED), e.reasons)
        assertNothingLeft(out)
    }

    @Test fun failedSplitSignLeavesNoOutputs() {
        val real = apksigSigner(user)
        var calls = 0
        val flaky = ApkSigner { unsigned, signed ->
            if (++calls == 2) {
                signed.writeText("half a split")
                throw PatchException.SignFailed("boom")
            }
            real.sign(unsigned, signed)
        }
        val out = tmp.newFolder()
        File(out, "keep.txt").writeText("not ours")
        try {
            GenericPatchEngine(flaky).patch(request(app(), out))
            fail("expected SignFailed")
        } catch (e: PatchException.SignFailed) {
            assertEquals("boom", e.message)
        }
        assertEquals(2, calls)
        assertNothingLeft(out, "keep.txt")
    }

    @Test fun wrongCertFailsVerify() {
        val out = tmp.newFolder()
        val e = refused<PatchException.SignFailed>(request(app(), out, cert = sha(other.cert.encoded)))
        assertTrue(e.message, "base.apk" in e.message!!)
        assertNothingLeft(out)
    }

    @Test fun tokenNotInToString() {
        val req = request(app())
        val printed = req.toString()
        assertFalse("the paywall token is a secret: $printed", TOKEN in printed)
        assertTrue(printed, printed.startsWith("GenericPatchRequest("))
        assertTrue(printed, "deviceSdk=36" in printed && "source=ORIGINAL" in printed)
        val result = engine.patch(req)
        assertFalse(TOKEN in result.toString())
    }

    @Test fun bytesNeededCoversTheFormula() {
        val b = bundle(module = ByteArray(4000), paywall = ByteArray(500))
        val apks = listOf(1000, 300, 200).map { n -> tmp.newFile().also { it.writeBytes(ByteArray(n)) } }
        // all APKs 1500, plus 2 x (base 1000 + modules 4500), plus 8 MiB
        assertEquals(1500L + 11_000L + 8_388_608L, GenericPatchEngine.bytesNeeded(apks, b))
    }

    @Test fun badInputListRefused() {
        val apks = app()
        val twin = File(tmp.newFolder(), "base.apk").also { apks[0].copyTo(it) }
        refused<PatchException.BadApk>(request(listOf(apks[0], twin)))
        // An output that would land on its own input (e.g. the saved original) is refused, and the input kept.
        val before = apks[1].readBytes()
        refused<PatchException.BadApk>(request(apks, out = apks[1].parentFile))
        assertArrayEquals(before, apks[1].readBytes())
        refused<PatchException.BadApk>(request(emptyList()))
    }

    @Test fun patchInfoReadsOnlyGeneric() {
        fun apk(info: String?) = zip(
            tmp.newFile(),
            listOf("AndroidManifest.xml" to baseManifest) + listOfNotNull(info?.let { GenericPatchInfo.ENTRY to it.toByteArray() }),
        )
        assertNull(GenericPatchInfo.read(apk(null)))
        assertNull("WhatsApp's patch-info", GenericPatchInfo.read(apk("""{"patcher_version":"3.0.0","revision":7}""")))
        assertNull(GenericPatchInfo.read(apk("not json")))
        try {
            GenericPatchInfo.read(apk("""{"kind":"generic","patcher_version":"1.0.0"}"""))
            fail("expected BadApk")
        } catch (e: PatchException.BadApk) {
            // expected
        }
        // A Refresh from a copy that isn't ours has nothing to go on.
        refused<PatchException.BadApk>(request(app(), source = GenericSource.PATCHED))
    }
}
