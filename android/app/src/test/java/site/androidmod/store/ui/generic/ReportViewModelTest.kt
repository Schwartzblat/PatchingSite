package site.androidmod.store.ui.generic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import site.androidmod.store.data.ApiClient
import site.androidmod.store.generic.GenericApi
import site.androidmod.store.generic.GenericRecord
import site.androidmod.store.generic.GenericReportRequest
import site.androidmod.store.generic.payloadLines
import site.androidmod.store.generic.reportPayload

@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModelTest {

    private lateinit var server: MockWebServer

    private val record = GenericRecord(
        packageName = "com.example.app",
        label = "Example",
        versionName = "7.1",
        versionCode = 71L,
        originalSigners = setOf("aa"),
        apkNames = listOf("base.apk"),
        patchedBundleId = "b1",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun model() = ReportViewModel(
        record = record,
        api = GenericApi(
            ApiClient(OkHttpClient(), baseUrl = { server.url("/").toString().trimEnd('/') }, token = { "tok" }),
        ),
        storeVersionCode = STORE_VERSION,
        sdkInt = SDK,
    )

    private fun thanks() = MockResponse(code = 201, body = """{"message":"Thanks! Your report was sent."}""")

    @Test
    fun nothing_sent_before_send_tap() = runTest {
        val model = model()
        assertTrue("nothing to preview before a choice", model.preview.isEmpty())
        assertFalse(model.canSend)

        model.onWorked(false)
        model.onNoteChange("The banner is still there.")

        assertTrue(model.preview.isNotEmpty())
        assertTrue(model.canSend)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun preview_equals_sent_body() = runTest {
        server.enqueue(thanks())
        val model = model()
        model.onWorked(true)
        model.onNoteChange("  No more ads at all.  ")
        val shown = model.preview

        model.send()
        val status = model.status.first { it.sent != null }

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/generic/reports", recorded.target)
        val sent = Json.decodeFromString<GenericReportRequest>(recorded.body!!.utf8())
        assertEquals(shown, payloadLines(sent))
        assertEquals(reportPayload(record, worked = true, note = "No more ads at all.", STORE_VERSION, SDK), sent)
        assertEquals(
            listOf(
                "App: Example",
                "Package: com.example.app",
                "Version: 7.1 (71)",
                "Result: It works",
                "Note: No more ads at all.",
                "Store version: $STORE_VERSION",
                "Android SDK: $SDK",
            ),
            shown,
        )
        assertEquals("Thanks! Your report was sent.", status.sent)
        assertFalse(status.sending)
    }

    @Test
    fun blank_note_is_sent_as_absent() = runTest {
        server.enqueue(thanks())
        val model = model()
        model.onWorked(false)
        model.onNoteChange("   \n ")

        assertFalse("no note line", model.preview.any { it.startsWith("Note:") })
        model.send()
        model.status.first { it.sent != null }

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertFalse(body.containsKey("note"))
        assertEquals("false", body["worked"].toString())
    }

    @Test
    fun note_over_500_disables_send() = runTest {
        val model = model()
        model.onWorked(true)

        model.onNoteChange("x".repeat(ReportViewModel.MAX_NOTE))
        assertTrue("500 is allowed", model.canSend)

        model.onNoteChange("x".repeat(ReportViewModel.MAX_NOTE) + "y")
        assertFalse(model.canSend)
        assertEquals(ReportViewModel.MAX_NOTE + 1, model.noteLength)
        model.send()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun note_length_is_counted_as_the_server_counts_it() {
        val model = model()
        model.onWorked(true)
        // 500 emoji are 1000 UTF-16 chars but 500 characters to Python; spaces around are stripped.
        model.onNoteChange("  " + "🙂".repeat(ReportViewModel.MAX_NOTE) + "  ")

        assertEquals(ReportViewModel.MAX_NOTE, model.noteLength)
        assertTrue(model.canSend)
    }

    @Test
    fun send_needs_a_choice() = runTest {
        val model = model()
        model.onNoteChange("Works fine.")

        assertFalse(model.canSend)
        model.send()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun rate_limited_message() = runTest {
        server.enqueue(MockResponse(code = 429, body = """{"detail":"Too many requests, please try again later."}"""))
        val model = model()
        model.onWorked(true)
        model.onNoteChange("Works.")

        model.send()
        val status = model.status.first { it.error != null }

        assertEquals("You've sent several reports today. Try again tomorrow.", status.error)
        assertEquals(true, model.worked)
        assertEquals("Works.", model.note)
    }

    @Test
    fun error_keeps_form() = runTest {
        server.enqueue(MockResponse(code = 503, body = """{"detail":"Patching other apps isn't available right now."}"""))
        val model = model()
        model.onWorked(false)
        model.onNoteChange("It crashes on start.")
        val shown = model.preview

        model.send()
        val status = model.status.first { it.error != null }

        assertEquals("Patching other apps isn't available right now.", status.error)
        assertNull(status.sent)
        assertFalse(status.sending)
        assertEquals(false, model.worked)
        assertEquals("It crashes on start.", model.note)
        assertEquals(shown, model.preview)
        assertTrue("it can be sent again", model.canSend)

        model.onNoteChange("It crashes on start, every time.")
        assertNull("editing clears the error", model.status.value.error)
    }

    @Test
    fun the_payload_carries_the_record_and_the_phone() {
        val request = reportPayload(record, worked = false, note = " ", storeVersionCode = 9L, sdkInt = 33)

        assertEquals(
            GenericReportRequest(
                packageName = "com.example.app",
                appName = "Example",
                versionName = "7.1",
                versionCode = 71L,
                worked = false,
                note = null,
                storeVersionCode = 9L,
                androidSdk = 33,
            ),
            request,
        )
        assertEquals(
            listOf(
                "App: Example",
                "Package: com.example.app",
                "Version: 7.1 (71)",
                "Result: It doesn't work",
                "Store version: 9",
                "Android SDK: 33",
            ),
            payloadLines(request),
        )
    }

    private companion object {
        const val STORE_VERSION = 389_123L
        const val SDK = 36
    }
}
