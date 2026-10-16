package site.androidmod.patch

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.apache.commons.compress.archivers.zip.ZipFile

data class BundleMeta(
    val format: Int,
    val packageName: String,
    val patcherVersion: String,
    val minSdk: Int,
    val minStoreVersionCode: Long,
    val dexOrder: List<String>,
    val files: Map<String, String>,
)

/** A verified patch bundle (Stitch `bundle.py`): every entry is read once, checked, and served from memory. */
class PatchBundle private constructor(
    val meta: BundleMeta,
    val features: Features,
    private val entries: Map<String, ByteArray>,
) {
    fun bytes(name: String): ByteArray =
        entries[name] ?: throw PatchException.BadBundle("bundle has no $name")

    companion object {
        private const val META = "bundle.json"
        private const val SIG = "bundle.sig"
        private val json = Json { ignoreUnknownKeys = true }

        fun open(zip: File, publicKey: PublicKey): PatchBundle {
            try {
                val all = LinkedHashMap<String, ByteArray>()
                ZipFile.builder().setFile(zip).get().use { z ->
                    for (entry in z.entries) {
                        if (all.containsKey(entry.name)) throw PatchException.BadBundle("duplicate entry ${entry.name}")
                        all[entry.name] = z.getInputStream(entry).use { it.readBytes() }
                    }
                }
                val metaBytes = all[META] ?: throw PatchException.BadBundle("bundle has no $META")
                val sig = all[SIG] ?: throw PatchException.BadBundle("bundle has no $SIG")
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(publicKey)
                verifier.update(metaBytes)
                if (!verifier.verify(sig)) throw PatchException.BadBundle("bad bundle signature")

                val obj = json.parseToJsonElement(metaBytes.toString(Charsets.UTF_8)).jsonObject
                val format = obj.getValue("format").jsonPrimitive.int
                if (format != 1) throw PatchException.BadBundle("unsupported bundle format: $format")
                val meta = BundleMeta(
                    format = format,
                    packageName = obj.getValue("package").jsonPrimitive.content,
                    patcherVersion = obj.getValue("patcher_version").jsonPrimitive.content,
                    minSdk = obj.getValue("min_sdk").jsonPrimitive.int,
                    minStoreVersionCode = obj.getValue("min_store_version_code").jsonPrimitive.long,
                    dexOrder = obj.getValue("dex_order").let { json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), it) },
                    files = (obj.getValue("files") as JsonObject).mapValues { it.value.jsonPrimitive.content },
                )
                val actual = all.keys - setOf(META, SIG)
                if (meta.files.keys != actual) {
                    throw PatchException.BadBundle("bundle files differ from $META: ${(meta.files.keys xor actual).sorted()}")
                }
                for ((name, digest) in meta.files) {
                    if (sha256Hex(all.getValue(name)) != digest) throw PatchException.BadBundle("hash mismatch for $name")
                }
                val features = json.decodeFromString(Features.serializer(), all.getValue("features.json").toString(Charsets.UTF_8))
                return PatchBundle(meta, features, all.filterKeys { it != META && it != SIG })
            } catch (e: PatchException) {
                throw e
            } catch (e: IOException) {
                throw PatchException.BadBundle("unreadable bundle: ${e.message}")
            } catch (e: GeneralSecurityException) {
                throw PatchException.BadBundle("bad bundle signature")
            } catch (e: RuntimeException) { // missing keys, bad JSON, wrong types
                throw PatchException.BadBundle("unreadable bundle: ${e.message}")
            }
        }

        private infix fun <T> Set<T>.xor(other: Set<T>): Set<T> = (this - other) + (other - this)

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** An X.509 SubjectPublicKeyInfo PEM holding an EC key. */
fun publicKeyFromPem(pem: String): PublicKey {
    val body = pem.lines().filter { !it.startsWith("-----") }.joinToString("")
    return try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(body)))
    } catch (e: GeneralSecurityException) {
        throw PatchException.BadBundle("bad public key: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw PatchException.BadBundle("bad public key: ${e.message}")
    }
}
