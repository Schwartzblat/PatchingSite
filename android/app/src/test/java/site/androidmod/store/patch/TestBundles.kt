package site.androidmod.store.patch

import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun newTestKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

const val MINIMAL_FEATURES_JSON =
    """{"required_keys":[],"official_cert_sha256":[],"features":[{"id":"f","title":"F","keys":[],"visible":true}]}"""

/** A catalog `site.androidmod.patch.AdCatalog` parses: one SDK, and one entry in each list. */
const val MINIMAL_CATALOG_JSON =
    """{"format":1,"sdks":[{"name":"AppLovin","packages":["com.applovin."],"hosts":["applovin.com"]}],""" +
        """"protected_hosts":["google.com"],"url_rules":[["www.google.com","/pagead/"]],""" +
        """"app_frame_packages":["com.unity3d.player."],"harvest_bare_tlds":["com"],"package_roots":["com"]}"""

/** Writes a patch bundle the engine accepts: files hashed in bundle.json, which [keyPair] signs. */
fun testBundleZip(
    file: File,
    pkg: String = "com.whatsapp",
    patcherVersion: String = "3.0.0",
    features: String = MINIMAL_FEATURES_JSON,
    minSdk: Int = 33,
    minStoreVersionCode: Long = 0,
    keyPair: KeyPair = newTestKeyPair(),
): File = signedBundleZip(
    file,
    files = linkedMapOf("module.apk" to "module".toByteArray(), "features.json" to features.toByteArray()),
    pkg = pkg,
    patcherVersion = patcherVersion,
    dexOrder = listOf("module.apk"),
    minSdk = minSdk,
    minStoreVersionCode = minStoreVersionCode,
    keyPair = keyPair,
)

/** Writes the generic bundle: package `generic`, `catalog.json` and no `features.json`, signed by [keyPair]. */
fun genericTestBundleZip(
    file: File,
    patcherVersion: String = "1.0.0",
    catalog: String = MINIMAL_CATALOG_JSON,
    minSdk: Int = 33,
    minStoreVersionCode: Long = 0,
    keyPair: KeyPair = newTestKeyPair(),
): File = signedBundleZip(
    file,
    files = linkedMapOf(
        "module.apk" to "module".toByteArray(),
        "paywall.apk" to "paywall".toByteArray(),
        "catalog.json" to catalog.toByteArray(),
    ),
    pkg = "generic",
    patcherVersion = patcherVersion,
    dexOrder = listOf("module.apk", "paywall.apk"),
    minSdk = minSdk,
    minStoreVersionCode = minStoreVersionCode,
    keyPair = keyPair,
)

private fun signedBundleZip(
    file: File,
    files: Map<String, ByteArray>,
    pkg: String,
    patcherVersion: String,
    dexOrder: List<String>,
    minSdk: Int,
    minStoreVersionCode: Long,
    keyPair: KeyPair,
): File {
    val hashes = files.entries.joinToString(",") { (name, bytes) ->
        "\"$name\":\"" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + "\""
    }
    val order = dexOrder.joinToString(",") { "\"$it\"" }
    val meta = """{"format":1,"package":"$pkg","patcher_version":"$patcherVersion","min_sdk":$minSdk,""" +
        """"min_store_version_code":$minStoreVersionCode,"dex_order":[$order],"files":{$hashes}}"""
    val metaBytes = meta.toByteArray()
    val sig = Signature.getInstance("SHA256withECDSA").apply {
        initSign(keyPair.private)
        update(metaBytes)
    }.sign()
    file.absoluteFile.parentFile?.mkdirs()
    ZipOutputStream(file.outputStream()).use { out ->
        for ((name, bytes) in files + mapOf("bundle.json" to metaBytes, "bundle.sig" to sig)) {
            out.putNextEntry(ZipEntry(name))
            out.write(bytes)
            out.closeEntry()
        }
    }
    return file
}
