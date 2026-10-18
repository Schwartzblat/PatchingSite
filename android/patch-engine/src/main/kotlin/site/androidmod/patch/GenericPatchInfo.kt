package site.androidmod.patch

import java.io.File
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.commons.compress.archivers.zip.ZipFile

/**
 * The record a generic patch leaves in the base ([ENTRY]), so the store can tell its own patches apart and a
 * Refresh can start from the patched copy. [kind] is always [KIND]. [added] is every entry the build added,
 * sorted and including [ENTRY] itself (the replaced `AndroidManifest.xml` aside; the app's own manifest is kept
 * as [ORIGINAL_MANIFEST]).
 */
@Serializable
data class GenericPatchInfo(
    val kind: String,
    @SerialName("patcher_version") val patcherVersion: String,
    @SerialName("bundle_id") val bundleId: String,
    @SerialName("catalog_format") val catalogFormat: Int,
    val abi: String,
    @SerialName("lib_dir") val libDir: String,
    @SerialName("sdks_present") val sdksPresent: String,
    val added: List<String>,
) {
    companion object {
        const val KIND = "generic"
        const val ENTRY = "assets/patcher/patch-info.json"
        const val ORIGINAL_MANIFEST = "assets/patcher/original-manifest.axml"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The generic patch-info in [apk], or null when it has none or another kind (e.g. a WhatsApp patch's).
         * A generic one that doesn't parse is [PatchException.BadApk].
         */
        fun read(apk: File): GenericPatchInfo? {
            val bytes = try {
                ZipFile.builder().setFile(apk).get().use { z ->
                    z.getEntry(ENTRY)?.let { e -> z.getInputStream(e).use { it.readBytes() } }
                }
            } catch (e: IOException) {
                throw PatchException.BadApk("Cannot read ${apk.name}: ${e.message}", e)
            } ?: return null
            val obj = try {
                json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            } catch (e: IllegalArgumentException) { // SerializationException: not JSON at all
                null
            } ?: return null
            if ((obj["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content != KIND) return null
            return try {
                json.decodeFromJsonElement(serializer(), obj)
            } catch (e: IllegalArgumentException) {
                throw PatchException.BadApk("${apk.name} has a malformed patch-info: ${e.message}", e)
            }
        }

        internal fun encode(info: GenericPatchInfo): ByteArray = Json.encodeToString(serializer(), info).toByteArray()
    }
}
