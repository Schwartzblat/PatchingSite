package site.androidmod.store.patch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import site.androidmod.store.data.ApiClient
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.decode
import java.io.File
import java.net.URLEncoder

/** The server's on-device patching endpoints (PatchingSite/README.md). */
class PatchApi(private val api: ApiClient) {

    /** Throws [StoreUpdateRequired] on 426; 401/402/404 stay [ApiException]. */
    suspend fun latest(pkg: String): LatestPatch = try {
        api.decode(api.get("/api/patch/${segment(pkg)}/latest"))
    } catch (error: ApiException) {
        if (error.status == HTTP_UPGRADE_REQUIRED) {
            throw StoreUpdateRequired(minStoreVersionCode(error.body))
        }
        throw error
    }

    /**
     * The full record for one build, as raw JSON for `VersionRecord.parse`, or null
     * when the server does not know the build. Exactly one of the two hashes.
     */
    suspend fun record(
        pkg: String,
        patcherVersion: String,
        apkSha256: String? = null,
        dexSha256: String? = null,
    ): String? {
        require((apkSha256 == null) != (dexSha256 == null)) { "Give exactly one of apkSha256 and dexSha256" }
        val hash = if (apkSha256 != null) "apk_sha256=${query(apkSha256)}" else "dex_sha256=${query(dexSha256!!)}"
        return try {
            api.get("/api/patch/${segment(pkg)}/artifacts?patcher_version=${query(patcherVersion)}&$hash")
        } catch (error: ApiException) {
            if (error.status == HTTP_NOT_FOUND) null else throw error
        }
    }

    /**
     * Downloads the patcher bundle to [target] unless the server says the copy
     * with [cachedSha256] is current. Returns the new bundle's SHA-256, or null when
     * not modified.
     */
    suspend fun bundle(pkg: String, patcherVersion: String, target: File, cachedSha256: String?): String? =
        api.fetchFile(
            "/api/patch/${segment(pkg)}/bundle/${segment(patcherVersion)}",
            target,
            cachedSha256?.let { "\"$it\"" },
        )

    suspend fun appToken(pkg: String): String {
        val body = buildJsonObject { put("package", pkg) }.toString()
        return api.decode<AppTokenResponse>(api.post("/api/app-token", body)).token
    }

    suspend fun signingKey(): SigningKeyResponse = api.decode(api.get("/api/signing-key"))

    /** 0 for a body that says nothing usable: an object or array there must not throw out of [latest]. */
    private fun minStoreVersionCode(body: String?): Long {
        val root = runCatching { Json.parseToJsonElement(body.orEmpty()).jsonObject }.getOrNull()
        return (root?.get("min_store_version_code") as? JsonPrimitive)?.longOrNull ?: 0L
    }

    private fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun query(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_UPGRADE_REQUIRED = 426
    }
}
