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

/** Writes a patch bundle the engine accepts: files hashed in bundle.json, which [keyPair] signs. */
fun testBundleZip(
    file: File,
    pkg: String = "com.whatsapp",
    patcherVersion: String = "3.0.0",
    features: String = MINIMAL_FEATURES_JSON,
    minSdk: Int = 33,
    minStoreVersionCode: Long = 0,
    keyPair: KeyPair = newTestKeyPair(),
): File {
    val files = linkedMapOf(
        "module.apk" to "module".toByteArray(),
        "features.json" to features.toByteArray(),
    )
    val hashes = files.entries.joinToString(",") { (name, bytes) ->
        "\"$name\":\"" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + "\""
    }
    val meta = """{"format":1,"package":"$pkg","patcher_version":"$patcherVersion","min_sdk":$minSdk,""" +
        """"min_store_version_code":$minStoreVersionCode,"dex_order":["module.apk"],"files":{$hashes}}"""
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
