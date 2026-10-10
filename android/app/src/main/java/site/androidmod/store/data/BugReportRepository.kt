package site.androidmod.store.data

import kotlinx.serialization.encodeToString

/**
 * Sends a bug report. The endpoint takes the bearer token when there is one and
 * works without it, so a report can be sent from any state the app is in.
 */
class BugReportRepository(private val api: ApiClient) {

    /** Returns the server's confirmation, for the screen to show. */
    suspend fun send(message: String, email: String?, context: Map<String, String>): String {
        val body = api.json.encodeToString(BugReportRequest(message, email, SOURCE, context))
        val response: BugReportResponse = api.decode(api.post("/api/bug-reports", body))
        return response.message
    }

    private companion object {
        /** Tells the owner which client a report came from; the website sends "web". */
        const val SOURCE = "android"
    }
}
