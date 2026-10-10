package site.androidmod.store.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import site.androidmod.store.data.BugReportRepository
import site.androidmod.store.update.InstalledBuild

@OptIn(ExperimentalCoroutinesApi::class)
class BugReportViewModelTest {

    private lateinit var server: MockWebServer
    private val context = mapOf("app_version" to "1.0 (389123)", "os" to "Android 16 (API 36)")

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

    private fun viewModel(email: String? = null) = BugReportViewModel(
        repository = BugReportRepository(
            ApiClient(OkHttpClient(), baseUrl = { server.url("/").toString().trimEnd('/') }, token = { "tok" })
        ),
        deviceContext = context,
        defaultEmail = email,
    )

    private fun sentBody(): JsonObject =
        Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    @Test
    fun `sends the report with the device context, marked as from android`() = runTest {
        server.enqueue(MockResponse(code = 201, body = """{"id":12,"message":"Thanks! Your report was sent."}"""))
        val model = viewModel()
        model.onMessageChange("  The install button does nothing.  ")
        model.onEmailChange(" me@example.com ")

        model.send()
        model.status.first { it.sent != null }

        val recorded = server.takeRequest()
        assertEquals("/api/bug-reports", recorded.target)
        assertEquals("POST", recorded.method)
        val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("The install button does nothing.", body["message"]?.jsonPrimitive?.content)
        assertEquals("me@example.com", body["email"]?.jsonPrimitive?.content)
        assertEquals("android", body["source"]?.jsonPrimitive?.content)
        assertEquals("Android 16 (API 36)", body["context"]?.jsonObject?.get("os")?.jsonPrimitive?.content)
    }

    @Test
    fun `an empty email is left out, so the server replies to the account's`() = runTest {
        server.enqueue(MockResponse(code = 201, body = """{"id":1,"message":"Thanks! Your report was sent."}"""))
        val model = viewModel()
        model.onMessageChange("Something broke on the Library tab.")

        model.send()
        model.status.first { it.sent != null }

        assertFalse(sentBody().containsKey("email"))
    }

    @Test
    fun `the signed-in account's email is filled in`() {
        assertEquals("alice@example.com", viewModel(email = "alice@example.com").email)
        assertEquals("", viewModel(email = null).email)
    }

    @Test
    fun `success shows the server's thanks and clears the form`() = runTest {
        server.enqueue(MockResponse(code = 201, body = """{"id":12,"message":"Thanks! Your report was sent."}"""))
        val model = viewModel()
        model.onMessageChange("The app crashes when I open Settings.")

        model.send()
        val state = model.status.first { it.sent != null }

        assertEquals("Thanks! Your report was sent.", state.sent)
        assertEquals("", model.message)
        assertFalse(state.sending)
    }

    @Test
    fun `rate limiting says to wait and offers email instead`() = runTest {
        server.enqueue(MockResponse(code = 429, body = """{"detail":"Too many requests"}"""))
        val model = viewModel()
        model.onMessageChange("The download never finishes.")

        model.send()
        val state = model.status.first { it.error != null }

        assertEquals(BugReportViewModel.RATE_LIMITED, state.error)
        assertEquals("nothing typed is lost", "The download never finishes.", model.message)
    }

    @Test
    fun `a failed send keeps the form and shows why`() = runTest {
        server.enqueue(MockResponse(code = 500, body = """{"detail":"Could not save the report"}"""))
        val model = viewModel()
        model.onMessageChange("The download never finishes.")

        model.send()
        val state = model.status.first { it.error != null }

        assertEquals("Could not save the report", state.error)
        assertEquals("The download never finishes.", model.message)
        assertNull(state.sent)
        assertTrue("it can be sent again", model.canSend)
    }

    @Test
    fun `a message shorter than the server accepts cannot be sent`() = runTest {
        val model = viewModel()
        model.onMessageChange("   broken   ")

        assertFalse(model.canSend)
        model.send()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `length is counted the way the server counts it`() {
        // Five emoji are ten UTF-16 chars but five characters to Python, which
        // would answer 422 to a report the form had let through.
        val model = viewModel()
        model.onMessageChange("🐛🐛🐛🐛🐛")
        assertFalse(model.canSend)
        assertEquals(5, model.messageLength)

        model.onMessageChange("x".repeat(BugReportViewModel.MAX_MESSAGE) + "y")
        assertFalse("over the limit", model.canSend)
        assertEquals(BugReportViewModel.MAX_MESSAGE + 1, model.messageLength)
    }

    @Test
    fun `an email that cannot be replied to is caught before sending`() {
        val model = viewModel()
        model.onMessageChange("The app crashes when I open Settings.")

        model.onEmailChange("not-an-email")
        assertFalse(model.canSend)
        assertTrue(model.emailInvalid)

        model.onEmailChange("")
        assertTrue("the email is optional", model.canSend)
    }

    @Test
    fun `the context names the build, the OS and the device`() {
        val context = bugReportContext(
            installed = InstalledBuild(versionCode = 389123, versionName = "1.0"),
            release = "16",
            sdk = 36,
            manufacturer = "Google",
            model = "Pixel 9a",
        )

        assertEquals(
            mapOf(
                "app_version" to "1.0 (389123)",
                "os" to "Android 16 (API 36)",
                "device" to "Google Pixel 9a",
            ),
            context,
        )
    }

    @Test
    fun `a support email carries what was typed and the same context`() {
        val device = mapOf("app_version" to "1.0 (389123)", "os" to "Android 16 (API 36)", "device" to "Google Pixel 9a")

        assertEquals(
            "It crashes.\n\n---\nPatched Store 1.0 (389123)\nAndroid 16 (API 36)\nGoogle Pixel 9a",
            supportEmailBody(device, "  It crashes. "),
        )
        assertTrue("room to type above the context", supportEmailBody(device).startsWith("\n\n---\n"))
    }

    @Test
    fun `a model that already starts with its maker is not doubled`() {
        val context = bugReportContext(InstalledBuild(1, "1.0"), "14", 34, "samsung", "Samsung Galaxy S23")

        assertEquals("Samsung Galaxy S23", context["device"])
    }
}
