package site.androidmod.patch

import com.android.apksig.ApkVerifier
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
        val other by lazy { TestKeys.generate("other") }
        const val TOKEN = "tok\"en-secret-1"
        const val PKG = GenericFixtures.PKG
        val V1 = Regex("META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))")
        fun hook(abi: String) = GenericFixtures.hook(abi)
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }

    private val fx = GenericFixtures(tmp)
    private val moduleDex = fx.moduleDex
    private val paywallDex = fx.paywallDex
    private val appDex2 = fx.appDex2
    private val baseManifest = fx.baseManifest
    private val work by lazy { File(tmp.root, "work") }
    private val engine by lazy { GenericPatchEngine(apksigSigner(user)) }

    private fun bundle(
        patcherVersion: String = "1.0.0",
        module: ByteArray = fx.moduleApk(),
        paywall: ByteArray = fx.paywallApk(),
        pkg: String = PatchBundle.GENERIC_PACKAGE,
    ) = fx.bundle(patcherVersion, module, paywall, pkg)

    private val bundle by lazy { bundle() }

    private fun app(
        classes: List<TinyClass> = fx.appClasses,
        baseExtra: List<Pair<String, ByteArray>> = emptyList(),
    ) = fx.app(classes, baseExtra)

    /** A generic bundle whose catalog is [catalog], for the catalog-shape variants. */
    private fun bundleWithCatalog(catalog: ByteArray): PatchBundle {
        val files = mapOf(
            "module.apk" to fx.moduleApk(),
            "paywall.apk" to fx.paywallApk(),
            "manifest-additions.xml" to GenericFixtures::class.java.getResource("/generic/manifest-additions.xml")!!.readBytes(),
            "catalog.json" to catalog,
        )
        val meta = mapOf(
            "format" to 1, "package" to PatchBundle.GENERIC_PACKAGE, "patcher_version" to "1.0.0", "min_sdk" to 33,
            "min_store_version_code" to 0, "dex_order" to listOf("module.apk", "paywall.apk"),
        )
        return PatchBundle.open(bundleZip(tmp.newFile(), files, meta, fx.ec.private), fx.ec.public)
    }

    /** The real fixture catalog with its `iap` section removed, as an old bundle would carry it. */
    private fun bundleWithoutIap(): PatchBundle {
        val catalog = GenericFixtures::class.java.getResource("/generic/catalog.json")!!.readText()
            .let(Json::parseToJsonElement).jsonObject.toMutableMap().apply { remove("iap") }
        return bundleWithCatalog(JsonObject(catalog).toString().toByteArray())
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
        capabilities: Set<Capability> = setOf(Capability.ADS),
        refused: Set<String> = emptySet(),
    ) = GenericPatchRequest(apks, source, bundle, token, cert, sdk, abis, capabilities, work, out, refused)

    private fun <T> open(f: File, block: (ZipFile) -> T): T = ZipFile.builder().setFile(f).get().use(block)
    private fun read(f: File, name: String): ByteArray? =
        open(f) { z -> z.getEntry(name)?.let { z.getInputStream(it).readBytes() } }
    private fun names(f: File) = open(f) { z -> z.entries.asSequence().map { it.name }.toList() }

    private fun artifacts(apk: File): Map<String, String> =
        Json.parseToJsonElement(String(read(apk, "assets/patcher/artifactory.json")!!)).jsonObject
            .mapValues { it.value.jsonPrimitive.content }

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

    /** Two zips with the same entry names, in order, and the same contents (their timestamps are ignored). */
    private fun assertSameZipContents(label: String, a: ByteArray, b: ByteArray) {
        fun contents(bytes: ByteArray) = java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            generateSequence { z.nextEntry }.map { it.name to z.readBytes().toList() }.toList()
        }
        val (ca, cb) = contents(a) to contents(b)
        assertTrue(label, ca.isNotEmpty())
        assertEquals(label, ca.map { it.first }, cb.map { it.first })
        for ((x, y) in ca.zip(cb)) assertEquals("$label ${x.first}", x.second, y.second)
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
        for (name in names(b1) - "assets/paywall.json") {
            // ApkBuilder stamps the current time, so a nested APK's bytes differ by its DOS times: compare what's inside.
            if (name.startsWith("assets/stitch/") && name.endsWith(".apk")) assertSameZipContents(name, read(b1, name)!!, read(b2, name)!!)
            else assertArrayEquals(name, read(b1, name), read(b2, name))
        }
        assertEquals(first.info, second.info)
        assertEquals(first.facts, second.facts)
        for (i in 1 until first.apks.size) assertSameBesidesSignature(first.apks[i], second.apks[i])
        second.apks.forEach { assertSignedBy(it, user) }
    }

    @Test fun adsOnlyArtifactsEqualTheScan() {
        val apks = app()
        val result = engine.patch(request(apks, capabilities = setOf(Capability.ADS)))
        val scan = AdScan.scan(apks, genericCatalog()).artifacts
        assertEquals(scan, result.artifacts - OriginalCert.KEY)
        assertTrue(OriginalCert.KEY in result.artifacts)
        assertEquals(
            setOf(
                "AD_SDKS_PRESENT", "AD_SDK_PACKAGES", "AD_APP_FRAME_PACKAGES", "AD_HOST_SUFFIXES",
                "AD_HOSTS_HARVESTED", "AD_PROTECTED_SUFFIXES", "AD_URL_RULES", "AD_CALLER_BLOCK",
            ),
            result.artifacts.keys - OriginalCert.KEY,
        )
        assertEquals("AppLovin", result.artifacts.getValue("AD_SDKS_PRESENT"))
        assertEquals("true", result.artifacts.getValue("AD_CALLER_BLOCK"))
        assertEquals("", result.artifacts.getValue("AD_HOSTS_HARVESTED"))
        assertTrue(result.artifacts.keys.none { it.startsWith("IAP_") })
        assertEquals(result.artifacts, artifacts(result.apks[0]))
        // The defaulted capability is still written: the key is present, not merely implied on read.
        val written = Json.parseToJsonElement(String(read(result.apks[0], GenericPatchInfo.ENTRY)!!)).jsonObject
        assertEquals("""["ads"]""", written.getValue("capabilities").toString())
    }

    @Test fun bothCapabilitiesWriteBothPrefixes() {
        val apks = app()
        val caps = setOf(Capability.ADS, Capability.IAP)
        val result = engine.patch(request(apks, capabilities = caps))
        assertEquals("true", result.artifacts.getValue("IAP_ENABLED"))
        assertEquals("", result.artifacts.getValue("IAP_CLIENTS_PRESENT"))
        assertEquals("AppLovin", result.artifacts.getValue("AD_SDKS_PRESENT"))
        assertEquals(
            IapScan.scan(apks, genericCatalog().iap).artifacts,
            result.artifacts.filterKeys { it.startsWith("IAP_") && it != "IAP_ENABLED" },
        )
        assertEquals(listOf("ads", "iap"), result.info.capabilities)
        assertEquals(listOf("ads", "iap"), GenericPatchInfo.read(result.apks[0])!!.capabilities)
        assertEquals(result.artifacts, artifacts(result.apks[0]))
    }

    @Test fun iapOnlyWritesNoAdKeys() {
        val apks = app()
        val result = engine.patch(request(apks, capabilities = setOf(Capability.IAP)))
        assertTrue(result.artifacts.toString(), result.artifacts.keys.none { it.startsWith("AD_") })
        assertEquals("true", result.artifacts.getValue("IAP_ENABLED"))
        val scan = IapScan.scan(apks, genericCatalog().iap).artifacts
        assertTrue(OriginalCert.KEY in result.artifacts)
        assertEquals(mapOf("IAP_ENABLED" to "true") + scan, result.artifacts - OriginalCert.KEY)
        assertEquals(scan, result.artifacts - "IAP_ENABLED" - OriginalCert.KEY)
        assertEquals(result.artifacts, artifacts(result.apks[0]))
        assertEquals(listOf("iap"), result.info.capabilities)
        assertEquals(listOf("iap"), GenericPatchInfo.read(result.apks[0])!!.capabilities)
    }

    @Test fun iapOnlyStillDetectsPairip() {
        val out = tmp.newFolder()
        val e = refused<PatchException.Unpatchable>(
            request(
                app(baseExtra = listOf("lib/arm64-v8a/libpairipcore.so" to ByteArray(8))),
                out,
                capabilities = setOf(Capability.IAP),
            ),
        )
        assertEquals(listOf(CantPatch.PAIRIP), e.reasons)
        assertNothingLeft(out)
    }

    @Test fun refreshFlowsUnchanged() {
        val caps = setOf(Capability.ADS, Capability.IAP)
        val first = engine.patch(request(app(), capabilities = caps))
        val second = engine.patch(request(first.apks, source = GenericSource.PATCHED, token = "new-token", capabilities = caps))
        assertEquals(listOf("ads", "iap"), first.info.capabilities)
        assertEquals(listOf("ads", "iap"), second.info.capabilities)
        assertEquals(listOf("ads", "iap"), GenericPatchInfo.read(first.apks[0])!!.capabilities)
        assertEquals(listOf("ads", "iap"), GenericPatchInfo.read(second.apks[0])!!.capabilities)
        assertEquals(first.info, second.info)
        assertEquals(first.artifacts, second.artifacts)
        assertEquals("true", second.artifacts["IAP_ENABLED"])
    }

    private fun sha1Upper(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02X".format(it) }

    @Test fun originalCertFromTheOriginal() {
        val result = engine.patch(request(app()))
        val want = sha1Upper(GenericFixtures.dev.cert.encoded)
        assertEquals(want, artifacts(result.apks[0])["ORIGINAL_CERT_SHA1"])
        assertEquals(want, result.artifacts["ORIGINAL_CERT_SHA1"])
    }

    @Test fun originalCertCarriedOnRefresh() {
        val first = engine.patch(request(app()))
        assertSignedBy(first.apks[0], user)
        val second = engine.patch(request(first.apks, source = GenericSource.PATCHED, token = "new-token"))
        assertEquals(sha1Upper(GenericFixtures.dev.cert.encoded), artifacts(second.apks[0])["ORIGINAL_CERT_SHA1"])
        assertEquals(first.artifacts["ORIGINAL_CERT_SHA1"], second.artifacts["ORIGINAL_CERT_SHA1"])
    }

    @Test fun originalCertWhateverTheCapabilities() {
        val result = engine.patch(request(app(), capabilities = setOf(Capability.IAP)))
        assertEquals(sha1Upper(GenericFixtures.dev.cert.encoded), artifacts(result.apks[0])["ORIGINAL_CERT_SHA1"])
    }

    @Test fun emptyCapabilitySetRefused() {
        val out = tmp.newFolder()
        val e = refused<PatchException.Unsupported>(request(app(), out, capabilities = emptySet()))
        assertEquals("nothing to patch", e.message)
        assertNothingLeft(out)
        // The store's junk-name path ends at the same refusal: of() drops what it doesn't know, and empty is refused.
        assertEquals(emptySet<Capability>(), Capability.of(emptyList()))
        val throughOf = refused<PatchException.Unsupported>(
            request(app(), tmp.newFolder(), capabilities = Capability.of(listOf("junk"))),
        )
        assertEquals("nothing to patch", throughOf.message)
    }

    @Test fun activityEntersTheManifest() {
        val result = engine.patch(request(app()))
        val merged = ManifestPatcher.facts(read(result.apks[0], "AndroidManifest.xml")!!).componentNames
        assertTrue(merged.toString(), "com.smali_generator.PurchaseResultActivity" in merged)
    }

    /** A bundle without the `iap` section predates the purchase classes: a purchases patch from it would do nothing. */
    @Test fun iapWithoutCatalogSectionRefused() {
        for (caps in listOf(setOf(Capability.IAP), setOf(Capability.ADS, Capability.IAP))) {
            val out = tmp.newFolder()
            val e = refused<PatchException.Unsupported>(request(app(), out, bundle = bundleWithoutIap(), capabilities = caps))
            assertEquals("this bundle can't bypass in-app purchases", e.message)
            assertNothingLeft(out)
        }
        val adsOnly = engine.patch(request(app(), bundle = bundleWithoutIap()))
        assertEquals("AppLovin", adsOnly.artifacts.getValue("AD_SDKS_PRESENT"))
    }

    /** Not one of the brief's eight: an old patch-info with no capabilities key still opens as ads-only. */
    @Test fun absentCapabilitiesReadAsAds() {
        val apk = zip(
            tmp.newFile(),
            listOf(
                "AndroidManifest.xml" to baseManifest,
                GenericPatchInfo.ENTRY to (
                    """{"kind":"generic","patcher_version":"1.0.0","bundle_id":"b","catalog_format":1,""" +
                        """"abi":"arm64-v8a","lib_dir":"lib/arm64-v8a","sdks_present":"AppLovin","added":[]}"""
                    ).toByteArray(),
            ),
        )
        assertEquals(listOf("ads"), GenericPatchInfo.read(apk)!!.capabilities)
    }

    /** Also beyond the eight: the store may name capabilities this engine doesn't know; they are dropped, not refused. */
    @Test fun capabilityOfIgnoresUnknownNames() {
        assertEquals(setOf(Capability.ADS, Capability.IAP), Capability.of(listOf("iap", "unknown", "ads", "iap")))
        assertEquals(emptySet<Capability>(), Capability.of(listOf("ads2", "")))
        assertEquals(listOf(Capability.ADS, Capability.IAP), Capability.of(listOf("iap", "ads")).toList())
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
        val e = refused<PatchException.Unpatchable>(request(app(classes = fx.appClasses + TinyClass("Lcom/pairip/VMRunner;")), out))
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
        // a split bigger than the base: 1000 + 300 + 2500 = 3800
        val apks = listOf(1000, 300, 2500).map { n -> tmp.newFile().also { it.writeBytes(ByteArray(n)) } }
        // all APKs 3800, plus 2 x (base 1000 + modules 4500), plus the largest split 2500, plus 8 MiB
        assertEquals(3800L + 11_000L + 2500L + 8_388_608L, GenericPatchEngine.bytesNeeded(apks, b))
        // no splits: nothing extra
        assertEquals(1000L + 11_000L + 8_388_608L, GenericPatchEngine.bytesNeeded(apks.take(1), b))
    }

    private fun additionsWith(vararg providers: Pair<String, String>) =
        manifestWith("generic", providers = providers.toList())

    @Test fun foreignAuthoritiesStayAndGenericOnesAreRewritten() {
        val b = fx.bundle(additions = additionsWith("a.P" to "generic.one;other.two", "a.Q" to "plain.three"))
        val result = engine.patch(request(app(), bundle = b))
        assertEquals(
            listOf("$PKG.one;other.two", "plain.three"),
            ManifestPatcher.providerAuthorities(read(result.apks[0], "AndroidManifest.xml")!!).let { listOf(it[0] + ";" + it[1], it[2]) },
        )
    }

    @Test fun leftoverPlaceholderAuthorityRefusedBeforeAnyOutput() {
        // The app already declares the bundle's provider under its placeholder authority, so the merge keeps it as it is.
        val b = fx.bundle(additions = additionsWith("a.P" to "generic.one"))
        val appManifest = manifestWith(
            PKG, versionCode = 5, versionName = "1.0", targetSdk = 34, providers = listOf("a.P" to "generic.one"),
        )
        val out = tmp.newFolder()
        val e = refused<PatchException.BadBundle>(request(fx.app(manifest = appManifest), out, bundle = b))
        assertTrue(e.message, "generic.one" in e.message!!)
        assertNothingLeft(out)
    }

    @Test fun patchesAnAppWithItsOwnGenericProvider() {
        val appManifest = manifestWith(
            PKG, versionCode = 5, versionName = "1.0", targetSdk = 34,
            providers = listOf("generic.thirdparty.Provider" to "generic.thirdparty"),
        )
        val result = engine.patch(request(fx.app(manifest = appManifest)))
        val found = ManifestPatcher.providerAuthorities(read(result.apks[0], "AndroidManifest.xml")!!)
        assertTrue(found.toString(), "generic.thirdparty" in found)
        assertTrue(found.toString(), "$PKG.com.smali_generator.InitProviderGeneric" in found)
    }

    @Test fun patchesAnAppNamedGenericDotSomething() {
        val name = "generic.example.app"
        val manifest = manifestWith(name, versionCode = 5, versionName = "1.0", targetSdk = 34)
        val result = engine.patch(request(fx.app(manifest = manifest, pkg = name)))
        val found = ManifestPatcher.providerAuthorities(read(result.apks[0], "AndroidManifest.xml")!!)
        assertTrue(found.toString(), found.isNotEmpty() && found.all { it.startsWith("$name.") })
    }

    @Test fun refusesAPackageInRefusedPackages() {
        val out = tmp.newFolder()
        val e = refused<PatchException.Unpatchable>(request(app(), out, refused = setOf("other.app", PKG)))
        assertEquals(listOf(CantPatch.IN_OUR_STORE), e.reasons)
        assertNothingLeft(out)
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
