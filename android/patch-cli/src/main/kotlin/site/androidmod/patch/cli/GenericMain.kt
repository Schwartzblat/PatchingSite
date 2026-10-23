package site.androidmod.patch.cli

import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import site.androidmod.patch.AdScan
import site.androidmod.patch.ApkSigner
import site.androidmod.patch.Capability
import site.androidmod.patch.GenericPatchEngine
import site.androidmod.patch.GenericPatchRequest
import site.androidmod.patch.GenericSource
import site.androidmod.patch.IapScan
import site.androidmod.patch.PatchBundle
import site.androidmod.patch.PatchException
import site.androidmod.patch.publicKeyFromPem

internal val GENERIC_USAGE = """
    usage: patch-cli generic --base FILE [--split FILE]... --bundle FILE --bundle-key FILE
                             --fastsigner FILE --keystore FILE --keystore-pass-env NAME
                             [--keystore-alias NAME] [--key-pass-env NAME]
                             --abis A,B,... --sdk N [--token TOKEN] [--refresh]
                             [--capabilities ads,iap] --out-dir DIR
""".trimIndent()

internal val SCAN_USAGE = """
    usage: patch-cli scan --base FILE [--split FILE]... --bundle FILE --bundle-key FILE
                          [--capabilities ads,iap]
""".trimIndent()

private val GENERIC_REQUIRED = listOf(
    "base", "bundle", "bundle-key", "fastsigner", "keystore", "keystore-pass-env", "abis", "sdk", "out-dir",
)
private val GENERIC_OPTIONAL = listOf("split", "keystore-alias", "key-pass-env", "token", "refresh", "capabilities")
private val SCAN_REQUIRED = listOf("base", "bundle", "bundle-key")

/**
 * The `--capabilities` names, defaulting to ads. Wire names are comma-separated and
 * case-insensitive; an unknown (or empty) name is a usage error, unlike [Capability.of], which ignores it.
 */
internal fun capabilities(flag: String?): Set<Capability> {
    if (flag == null) return setOf(Capability.ADS)
    val names = flag.split(",").map { it.trim().lowercase() }
    if (names.any { it.isEmpty() }) throw UsageError("--capabilities: empty capability name in '$flag'")
    val unknown = names.firstOrNull { name -> Capability.entries.none { it.wire == name } }
    if (unknown != null) {
        throw UsageError("--capabilities: unknown capability '$unknown' (expected ${Capability.entries.joinToString { it.wire }})")
    }
    return Capability.of(names)
}

private fun apkFiles(flags: Map<String, List<String>>) =
    (flags.getValue("base") + flags["split"].orEmpty()).map { File(it) }

private fun requireFiles(flags: Map<String, List<String>>, names: List<String>) {
    for (flag in names) {
        for (path in flags.getValue(flag)) if (!File(path).isFile) throw UsageError("--$flag: no such file: $path")
    }
}

private fun openBundle(flags: Map<String, List<String>>) = PatchBundle.open(
    File(flags.getValue("bundle").single()),
    publicKeyFromPem(File(flags.getValue("bundle-key").single()).readText()),
)

/** SHA-256 of the signing certificate's DER, lowercase hex: the keystore's [alias], or its only entry. */
private fun signerCertSha256(keystore: File, password: CharArray, alias: String?): String {
    val store = KeyStore.getInstance("PKCS12")
    keystore.inputStream().use { store.load(it, password) }
    val name = alias ?: store.aliases().toList().let {
        it.singleOrNull() ?: throw UsageError("${keystore.name} holds ${it.size} aliases; pick one with --keystore-alias")
    }
    val cert = store.getCertificate(name) ?: throw UsageError("no certificate under alias '$name' in ${keystore.name}")
    return MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
}

internal fun generic(
    args: List<String>,
    env: Map<String, String>,
    signerFactory: (SignerConfig) -> ApkSigner,
    workDir: File,
): Int {
    val flags = parse(args, GENERIC_REQUIRED, GENERIC_OPTIONAL, repeatable = setOf("split"), switches = setOf("refresh"))
    fun one(flag: String) = flags.getValue(flag).single()
    val sdk = one("sdk").let { it.toIntOrNull() ?: throw UsageError("--sdk must be an integer, got '$it'") }
    val abis = one("abis").split(",")
    if (abis.any { it.isEmpty() }) throw UsageError("--abis must be a comma-separated list of ABIs, got '${one("abis")}'")
    val chosen = capabilities(flags["capabilities"]?.single())
    val password = envPassword(env, one("keystore-pass-env"), "keystore-pass-env")
    val config = SignerConfig(
        fastsigner = File(one("fastsigner")),
        keystore = File(one("keystore")),
        password = password,
        alias = flags["keystore-alias"]?.single(),
        keyPassword = flags["key-pass-env"]?.let { envPassword(env, it.single(), "key-pass-env") },
    )
    requireFiles(flags, listOf("base", "split", "bundle", "bundle-key").filter { it in flags })

    val started = System.nanoTime()
    val certSha = signerCertSha256(config.keystore, password, config.alias)
    val result = GenericPatchEngine(signerFactory(config)).patch(
        GenericPatchRequest(
            apks = apkFiles(flags),
            source = if ("refresh" in flags) GenericSource.PATCHED else GenericSource.ORIGINAL,
            bundle = openBundle(flags),
            paywallToken = flags["token"]?.single() ?: "dev",
            signerCertSha256 = certSha,
            deviceSdk = sdk,
            deviceAbis = abis,
            capabilities = chosen,
            workDir = workDir,
            outputDir = File(one("out-dir")),
        ),
    )
    val seconds = (System.nanoTime() - started) / 1_000_000_000.0
    result.apks.forEach { println("output: $it") }
    println("package: ${result.facts.packageName}")
    println("versionCode: ${result.facts.versionCode}")
    // The ad-only lines describe ads work: an ads-less patch has no such artifacts to report.
    if (Capability.ADS in chosen) {
        println("AD_SDKS_PRESENT: ${result.artifacts["AD_SDKS_PRESENT"]}")
        println("hostsHarvested: ${result.artifacts["AD_HOSTS_HARVESTED"].orEmpty().split(",").count { it.isNotEmpty() }}")
    }
    println("elapsed: %.1fs".format(seconds))
    return 0
}

private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

internal fun scan(args: List<String>): Int {
    val flags = parse(args, SCAN_REQUIRED, listOf("split", "capabilities"), repeatable = setOf("split"))
    val chosen = capabilities(flags["capabilities"]?.single())
    requireFiles(flags, listOf("base", "bundle", "bundle-key"))
    requireFiles(flags, listOf("split").filter { it in flags })
    val catalog = openBundle(flags).catalog
        ?: throw PatchException.BadBundle("not a generic bundle: it has no ad catalog")
    // What a patch would write: the same composition GenericPatchEngine.run makes, so scan shows it.
    val artifacts = LinkedHashMap<String, String>()
    if (Capability.ADS in chosen) artifacts.putAll(AdScan.scan(apkFiles(flags), catalog).artifacts)
    if (Capability.IAP in chosen) {
        artifacts["IAP_ENABLED"] = "true"
        artifacts.putAll(IapScan.scan(apkFiles(flags), catalog.iap).artifacts)
    }
    val sorted = JsonObject(artifacts.toSortedMap().mapValues { JsonPrimitive(it.value) })
    println(prettyJson.encodeToString(JsonObject.serializer(), sorted))
    return 0
}
