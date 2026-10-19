package site.androidmod.store.generic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One app on the server's Works-with list (GET /api/generic). */
@Serializable
data class WorksWithEntry(
    @SerialName("package") val packageName: String,
    val name: String,
    val note: String,
    @SerialName("tested_version") val testedVersion: String,
    @SerialName("tested_on") val testedOn: String,
)

/** GET /api/generic. */
@Serializable
data class GenericStatus(
    val enabled: Boolean,
    @SerialName("works_with") val worksWith: List<WorksWithEntry> = emptyList(),
)

/** GET /api/patch/generic/latest. */
@Serializable
data class GenericLatest(
    @SerialName("package") val packageName: String,
    @SerialName("patcher_version") val patcherVersion: String,
    @SerialName("bundle_id") val bundleId: String,
    val sha256: String,
    val size: Long,
    @SerialName("min_sdk") val minSdk: Int,
    @SerialName("min_store_version_code") val minStoreVersionCode: Long,
    val revision: Int,
)

/** The paywall token for the generic bundle. Secret: [toString] never shows it. */
class GenericToken(val token: String, val expiresAtMillis: Long) {
    override fun toString() = "GenericToken(expiresAtMillis=$expiresAtMillis)"
}

/** POST /api/generic/reports. [note] is sent only when it is not null. */
@Serializable
data class GenericReportRequest(
    @SerialName("package") val packageName: String,
    @SerialName("app_name") val appName: String,
    @SerialName("version_name") val versionName: String,
    @SerialName("version_code") val versionCode: Long,
    val worked: Boolean,
    val note: String? = null,
    @SerialName("store_version_code") val storeVersionCode: Long,
    @SerialName("android_sdk") val androidSdk: Int,
)

/** Wire shapes that are not part of the public interface. */
@Serializable
internal data class GenericTokenResponse(val token: String, @SerialName("expires_at") val expiresAt: String? = null) {
    override fun toString() = "GenericTokenResponse()"
}

@Serializable
internal data class GenericReportResponse(val message: String)
