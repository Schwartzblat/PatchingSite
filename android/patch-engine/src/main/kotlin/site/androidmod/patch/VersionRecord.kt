package site.androidmod.patch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the finders found for one app version (Stitch `records.build_record`). */
@Serializable
data class VersionRecord(
    @SerialName("package") val packageName: String,
    @SerialName("version_name") val versionName: String,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("apk_sha256") val apkSha256: String,
    @SerialName("dex_sha256") val dexSha256: String,
    @SerialName("patcher_version") val patcherVersion: String,
    val artifacts: Map<String, String>,
    @SerialName("missing_keys") val missingKeys: List<String>,
    @SerialName("features_off") val featuresOff: List<String>,
    val supported: Boolean,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(json: String): VersionRecord = this.json.decodeFromString(serializer(), json)
    }
}
