package site.androidmod.patch

import java.io.File
import java.util.Base64
import org.junit.rules.TemporaryFolder

/**
 * A synthetic installed app (base with an AppLovin class, two splits, signed by a developer key) and
 * a generic bundle for it, shared by the engine and CLI tests. Everything lands under [tmp].
 */
class GenericFixtures(private val tmp: TemporaryFolder) {
    companion object {
        const val PKG = "com.example.app"
        val HOOK_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        fun hook(abi: String) = ByteArray(32) { (HOOK_ABIS.indexOf(abi) + 20).toByte() }
        val dev by lazy { TestKeys.generate("developer") }
        private fun resource(name: String) = GenericFixtures::class.java.getResource(name)!!.readBytes()
    }

    val ec = ecKeyPair()

    /** The bundle-signing public key as a PEM, as `--bundle-key` reads it. */
    val publicKeyPem: String
        get() = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(ec.public.encoded) +
            "\n-----END PUBLIC KEY-----\n"

    val moduleDex = TinyDex.build(emptyList(), listOf(TinyClass("Lcom/smali_generator/InitProviderGeneric;")))
    val paywallDex = TinyDex.build(emptyList(), listOf(TinyClass("Lcom/paywall/InitProviderPaywallGeneric;")))
    val appClasses = listOf(
        TinyClass("Lcom/applovin/sdk/AppLovinSdk;", listOf(TinyMethod(TinyDex.constString(0)))),
        TinyClass("Lcom/example/app/Main;"),
    )
    val appDex2 = TinyDex.build(emptyList(), listOf(TinyClass("Lcom/example/app/Second;")))
    val baseManifest = manifestWith(
        PKG, versionCode = 5, versionName = "1.0", targetSdk = 34, components = listOf("activity" to "$PKG.Main"),
    )

    fun moduleApk(): ByteArray = zip(
        tmp.newFile(),
        listOf("AndroidManifest.xml" to SyntheticApks.manifest("com.smali_generator"), "classes.dex" to moduleDex) +
            HOOK_ABIS.map { "lib/$it/libarthooks.so" to hook(it) } +
            listOf("resources.arsc" to ByteArray(40) { 6 }),
    ).readBytes()

    fun paywallApk(): ByteArray = zip(
        tmp.newFile(),
        listOf(
            "AndroidManifest.xml" to SyntheticApks.manifest("com.paywall"),
            "classes.dex" to paywallDex,
            "resources.arsc" to ByteArray(56) { 8 },
        ),
    ).readBytes()

    /** The signed bundle file; [pkg] other than the generic one makes an app bundle (for refusal tests). */
    fun bundleFile(
        patcherVersion: String = "1.0.0",
        module: ByteArray = moduleApk(),
        paywall: ByteArray = paywallApk(),
        pkg: String = PatchBundle.GENERIC_PACKAGE,
        additions: ByteArray = resource("/generic/manifest-additions.xml"),
    ): File {
        val kindFile = if (pkg == PatchBundle.GENERIC_PACKAGE) {
            "catalog.json" to resource("/generic/catalog.json")
        } else {
            "features.json" to """{"required_keys":[],"official_cert_sha256":["00"],"features":[]}""".toByteArray()
        }
        val files = mapOf(
            "module.apk" to module,
            "paywall.apk" to paywall,
            "manifest-additions.xml" to additions,
            kindFile,
        )
        val meta = mapOf(
            "format" to 1, "package" to pkg, "patcher_version" to patcherVersion, "min_sdk" to 33,
            "min_store_version_code" to 0, "dex_order" to listOf("module.apk", "paywall.apk"),
        )
        return bundleZip(tmp.newFile(), files, meta, ec.private)
    }

    fun bundle(
        patcherVersion: String = "1.0.0",
        module: ByteArray = moduleApk(),
        paywall: ByteArray = paywallApk(),
        pkg: String = PatchBundle.GENERIC_PACKAGE,
        additions: ByteArray = resource("/generic/manifest-additions.xml"),
    ): PatchBundle = PatchBundle.open(bundleFile(patcherVersion, module, paywall, pkg, additions), ec.public)

    /** An installed-app APK, signed by its developer with v1 (so it carries signature files) as well as v2 and v3. */
    @Suppress("DEPRECATION")
    fun devSigned(dir: File, name: String, entries: List<Pair<String, ByteArray>>, stored: Boolean = false): File {
        val unsigned = zip(tmp.newFile(), entries, stored)
        val signed = File(dir, name)
        com.android.apksig.ApkSigner.Builder(
            listOf(com.android.apksig.ApkSigner.SignerConfig.Builder("dev", dev.privateKey, listOf(dev.cert)).build()),
        ).setInputApk(unsigned).setOutputApk(signed).setMinSdkVersion(33).setV1SigningEnabled(true).build().sign()
        return signed
    }

    /** The synthetic app: a base with an AppLovin class and no libs, and splits config.arm64_v8a and config.en. */
    fun app(
        classes: List<TinyClass> = appClasses,
        baseExtra: List<Pair<String, ByteArray>> = emptyList(),
        manifest: ByteArray = baseManifest,
        pkg: String = PKG,
    ): List<File> {
        val dir = tmp.newFolder()
        val base = devSigned(
            dir, "base.apk",
            listOf(
                "AndroidManifest.xml" to manifest,
                "classes.dex" to TinyDex.build(listOf("https://ms.applvn.com/x"), classes),
                "classes2.dex" to appDex2,
                "assets/app.txt" to "app asset".toByteArray(),
            ) + baseExtra,
        )
        fun split(name: String) = manifestWith(pkg, versionCode = 5, split = name)
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
}
