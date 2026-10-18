package site.androidmod.patch

import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

fun ecKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

private fun jsonOf(v: Any?): String = when (v) {
    null -> "null"
    is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    is Map<*, *> -> v.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") { jsonOf(it.key.toString()) + ":" + jsonOf(it.value) }
    is List<*> -> v.joinToString(",", "[", "]") { jsonOf(it) }
    else -> v.toString()
}

/**
 * Writes a bundle like Stitch: `bundle.json` is [meta] plus `files` (SHA-256 of each of [files]),
 * `bundle.sig` is DER ECDSA over its exact bytes. [extraEntries] are appended after, unlisted.
 */
fun bundleZip(
    file: File,
    files: Map<String, ByteArray>,
    meta: Map<String, Any?>,
    key: PrivateKey,
    extraEntries: List<Pair<String, ByteArray>> = emptyList(),
): File {
    val hashes = files.mapValues { MessageDigest.getInstance("SHA-256").digest(it.value).joinToString("") { b -> "%02x".format(b) } }
    val metaBytes = jsonOf(meta + ("files" to hashes)).toByteArray()
    val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(metaBytes) }.sign()
    return zip(file, files.map { it.key to it.value } + listOf("bundle.json" to metaBytes, "bundle.sig" to sig) + extraEntries)
}
