package site.androidmod.store.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A failed API call. [status] is 0 when the request never reached the server.
 */
class ApiException(val status: Int, override val message: String) : Exception(message)

val ApiException.isAuth: Boolean get() = status == 401
val ApiException.needsSubscription: Boolean get() = status == 402

/**
 * Worth asking again: the request never got an answer (status 0 - no network, a
 * dropped connection, or a body this app could not read) or the server failed
 * (5xx - including nginx's 502/503 while uvicorn restarts). A 4xx is the server's
 * considered answer to this request and would come back the same.
 */
val ApiException.isTransient: Boolean get() = status == 0 || status in 500..599

/**
 * Port of `messageFrom` in client/src/lib/api.js.
 *
 * FastAPI reports failures as {"detail": "..."} or, for 422s,
 * {"detail": [{loc, msg, type}, ...]}. Reading the wrong key swallows every real
 * message the server sends, which is a bug the web client already had once.
 */
object ApiErrors {

    private val json = Json { ignoreUnknownKeys = true }

    fun messageFrom(body: String?, status: Int): String {
        parse(body)?.let { return it }
        if (status == 0) return "Network error. Check your connection and try again."
        return "Request failed ($status)"
    }

    private fun parse(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null

        when (val detail = root["detail"]) {
            is JsonPrimitive -> detail.contentOrNullIfNotString()?.takeIf { it.isNotEmpty() }?.let { return it }
            is JsonArray -> fromValidationErrors(detail)?.let { return it }
            else -> Unit
        }

        val message = (root["message"] as? JsonPrimitive)?.contentOrNullIfNotString()
        return message?.takeIf { it.isNotEmpty() }
    }

    private fun fromValidationErrors(detail: JsonArray): String? {
        val parts = detail.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val msg = (item["msg"] as? JsonPrimitive)?.contentOrNullIfNotString() ?: return@mapNotNull null
            val field = (item["loc"] as? JsonArray)?.lastOrNull()?.locSegment()
            if (field.isNullOrEmpty()) msg else "$field: $msg"
        }
        // Deliberately NOT api.js's behaviour here. Once `detail` is a non-empty
        // array it returns the joined string unconditionally, so an array whose
        // items carry no `msg` renders as an EMPTY error message. Falling through
        // to the status line is the one place this port knowingly improves on the
        // original instead of copying it.
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * One `loc` segment. Pydantic reports it as a field name, or as a numeric
     * index when the error is on an element of a list-typed body
     * (`["body", "tags", 1]`). Both make usable labels; only a JSON null does not.
     *
     * api.js tests the segment for truthiness, which silently drops index 0 as
     * well. That is a JavaScript artifact rather than intent, so it is not copied.
     */
    private fun JsonElement.locSegment(): String? =
        (this as? JsonPrimitive)
            ?.takeUnless { it is JsonNull }
            ?.content
            ?.takeIf { it.isNotBlank() }

    /** `isString` guards against a JSON null rendering as the literal "null". */
    private fun JsonPrimitive.contentOrNullIfNotString(): String? = if (isString) content else null
}
