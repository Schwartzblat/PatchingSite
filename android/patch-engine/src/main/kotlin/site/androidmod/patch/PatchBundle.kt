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
    /** Null exactly for a generic bundle. */
    val features: Features?,
    /** Non-null exactly for a generic bundle. */
    val catalog: AdCatalog?,
    /** Lowercase hex SHA-256 of the `bundle.json` bytes. */
    val id: String,
    private val entries: Map<String, ByteArray>,
) {
    val isGeneric: Boolean get() = meta.packageName == GENERIC_PACKAGE

    fun bytes(name: String): ByteArray =
        entries[name] ?: throw PatchException.BadBundle("bundle has no $name")

    companion object {
        const val GENERIC_PACKAGE = "generic"
        private val CATALOG_FORMATS = setOf(1)
        private const val META = "bundle.json"
        private const val SIG = "bundle.sig"
        internal const val MAX_ENTRY_BYTES = 32L shl 20
        internal const val MAX_TOTAL_BYTES = 64L shl 20
        private val json = Json { ignoreUnknownKeys = true }

        fun open(zip: File, publicKey: PublicKey): PatchBundle {
            try {
                val all = LinkedHashMap<String, ByteArray>()
                var total = 0L
                ZipFile.builder().setFile(zip).get().use { z ->
                    for (entry in z.entries) {
                        if (all.containsKey(entry.name)) throw PatchException.BadBundle("duplicate entry ${entry.name}")
                        val data = z.getInputStream(entry).use { readCapped(it, entry.name, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - total) }
                        total += data.size
                        all[entry.name] = data
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
                for (name in all.keys + meta.files.keys + meta.dexOrder.filter { it !in meta.files }) checkName(name)
                for (name in meta.dexOrder) {
                    if (name !in meta.files) throw PatchException.BadBundle("dex_order names $name, which is not a listed file")
                }
                val actual = all.keys - setOf(META, SIG)
                if (meta.files.keys != actual) {
                    throw PatchException.BadBundle("bundle files differ from $META: ${(meta.files.keys xor actual).sorted()}")
                }
                for ((name, digest) in meta.files) {
                    if (sha256Hex(all.getValue(name)) != digest) throw PatchException.BadBundle("hash mismatch for $name")
                }
                val id = sha256Hex(metaBytes)
                val rest = all.filterKeys { it != META && it != SIG }
                if (meta.packageName == GENERIC_PACKAGE) {
                    if ("features.json" in rest) throw PatchException.BadBundle("a generic bundle must not have features.json")
                    val raw = rest["catalog.json"] ?: throw PatchException.BadBundle("a generic bundle needs catalog.json")
                    val text = raw.toString(Charsets.UTF_8)
                    val catalogFormat = json.parseToJsonElement(text).jsonObject.getValue("format").jsonPrimitive.int
                    if (catalogFormat !in CATALOG_FORMATS) throw PatchException.BadBundle("unsupported catalog format $catalogFormat")
                    val catalog = json.decodeFromString(AdCatalog.serializer(), text)
                    if (catalog.urlRules.any { it.size != 2 }) throw PatchException.BadBundle("catalog url_rules must each be [host, path]")
                    return PatchBundle(meta, null, catalog, id, rest)
                }
                val raw = rest["features.json"] ?: throw PatchException.BadBundle("bundle has no features.json")
                val features = json.decodeFromString(Features.serializer(), raw.toString(Charsets.UTF_8))
                return PatchBundle(meta, features, null, id, rest)
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

        private fun checkName(name: String) {
            if (name.isEmpty() || '/' in name || '\\' in name || ".." in name) throw PatchException.BadBundle("bad entry name: $name")
        }

        /** Reads [input] fully, counting bytes as they arrive (headers are not trusted); over either cap is a [PatchException.BadBundle]. */
        private fun readCapped(input: java.io.InputStream, name: String, entryCap: Long, totalLeft: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var n = 0L
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                n += r
                if (n > entryCap) throw PatchException.BadBundle("bundle entry $name exceeds ${entryCap shr 20} MiB")
                if (n > totalLeft) throw PatchException.BadBundle("bundle exceeds ${MAX_TOTAL_BYTES shr 20} MiB uncompressed")
                out.write(buf, 0, r)
            }
            return out.toByteArray()
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
