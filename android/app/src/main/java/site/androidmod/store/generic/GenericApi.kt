package site.androidmod.store.generic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import site.androidmod.store.data.ApiClient
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.decode
import site.androidmod.store.patch.StoreUpdateRequired
import java.time.Instant

/** The server's generic ad remover endpoints (PatchingSite/README.md). */
class GenericApi(private val api: ApiClient) {

    suspend fun status(): GenericStatus = api.decode(api.get("/api/generic"))

    /** Throws [StoreUpdateRequired] on 426; other non-2xx stay [ApiException]. */
    suspend fun latest(): GenericLatest = try {
        api.decode(api.get("/api/patch/generic/latest"))
    } catch (error: ApiException) {
        if (error.status == HTTP_UPGRADE_REQUIRED) {
            throw StoreUpdateRequired(minStoreVersionCode(error.body))
        }
        throw error
    }

    /** Fails with `ApiException(0, …)` when `expires_at` is missing or not ISO 8601 UTC. */
    suspend fun appToken(): GenericToken {
        val body = buildJsonObject { put("package", "generic") }.toString()
        val response = api.decode<GenericTokenResponse>(api.post("/api/app-token", body))
        val expiresAt = response.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: throw ApiException(0, "The server sent a response this app could not read.")
        return GenericToken(response.token, expiresAt)
    }

    /** Returns the server's thank-you message. */
    suspend fun report(request: GenericReportRequest): String =
        api.decode<GenericReportResponse>(api.post("/api/generic/reports", api.json.encodeToString(request))).message

    /** 0 for a body that says nothing usable: an object or array there must not throw out of [latest]. */
    private fun minStoreVersionCode(body: String?): Long {
        val root = runCatching { Json.parseToJsonElement(body.orEmpty()).jsonObject }.getOrNull()
        return (root?.get("min_store_version_code") as? JsonPrimitive)?.longOrNull ?: 0L
    }

    private companion object {
        const val HTTP_UPGRADE_REQUIRED = 426
    }
}
