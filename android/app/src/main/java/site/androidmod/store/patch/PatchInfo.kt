package site.androidmod.store.patch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

/** What the engine recorded in a build it made (`assets/patcher/patch-info.json`). */
@Serializable
data class PatchInfo(
    @SerialName("patcher_version") val patcherVersion: String,
    val revision: Int,
    @SerialName("dex_sha256") val dexSha256: String,
    @SerialName("features_off") val featuresOff: List<String> = emptyList(),
)

private const val PATCH_INFO_ENTRY = "assets/patcher/patch-info.json"
private const val MAX_PATCH_INFO_BYTES = 64 * 1024L
private val lenientJson = Json { ignoreUnknownKeys = true }

/** The patch info inside [apk], or null when it has none, is not a zip, or the entry is garbled. */
fun readPatchInfo(apk: File): PatchInfo? = try {
    ZipFile(apk).use { zip ->
        val entry = zip.getEntry(PATCH_INFO_ENTRY)
        if (entry == null || entry.size > MAX_PATCH_INFO_BYTES) {
            null
        } else {
            lenientJson.decodeFromString<PatchInfo>(zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }
} catch (error: Exception) {
    null
}
