package site.androidmod.store.patch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /api/patch/{pkg}/latest: the newest official build the server can patch, and how. */
@Serializable
data class LatestPatch(
    @SerialName("package") val packageName: String,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    @SerialName("apk_url") val apkUrl: String,
    @SerialName("apk_sha256") val apkSha256: String,
    @SerialName("dex_sha256") val dexSha256: String,
    @SerialName("patcher_version") val patcherVersion: String,
    val revision: Int,
    @SerialName("features_off") val featuresOff: List<String> = emptyList(),
)

/** GET /api/signing-key. Secret: never log or put into an exception. */
@Serializable
data class SigningKeyResponse(
    val pkcs12: String,
    val password: String,
    val alias: String,
    @SerialName("cert_sha256") val certSha256: String,
    @SerialName("cert_sha1") val certSha1: String,
) {
    override fun toString() = "SigningKeyResponse(alias=$alias, certSha256=$certSha256)"
}

/** POST /api/app-token. Secret, like the bearer token. */
@Serializable
data class AppTokenResponse(val token: String) {
    override fun toString() = "AppTokenResponse()"
}

/** The server refused (426) because this store is older than the patch needs. */
class StoreUpdateRequired(val minStoreVersionCode: Long) : Exception("Store update required")
