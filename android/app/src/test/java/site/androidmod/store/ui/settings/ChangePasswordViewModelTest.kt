package site.androidmod.store.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
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
import site.androidmod.store.data.AuthRepository
import site.androidmod.store.data.TokenHolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChangePasswordViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var tokens: FakeTokenStore
    private lateinit var auth: AuthRepository

    private class FakeTokenStore : TokenHolder {
        override var token: String? = "tok"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        tokens = FakeTokenStore()
        auth = AuthRepository(
            api = ApiClient(OkHttpClient(), baseUrl = { server.url("/").toString().trimEnd('/') }, token = { tokens.token }),
            tokens = tokens,
        )
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun filledIn(
        current: String = "old secret",
        new: String = "new secret",
        confirm: String = new,
    ) = ChangePasswordViewModel(auth).apply {
        onCurrentPasswordChange(current)
        onNewPasswordChange(new)
        onConfirmPasswordChange(confirm)
    }

    @Test
    fun `save is only offered for a change the server will take`() {
        val model = ChangePasswordViewModel(auth)
        assertFalse("nothing typed", model.formValid)

        model.onCurrentPasswordChange("old secret")
        model.onNewPasswordChange("short")
        model.onConfirmPasswordChange("short")
        assertFalse(model.formValid)
        assertEquals("At least 8 characters", model.newPasswordProblem)

        model.onNewPasswordChange("new secret")
        assertFalse(model.formValid)
        assertEquals("Passwords do not match.", model.confirmPasswordProblem)

        model.onConfirmPasswordChange("new secret")
        assertTrue(model.formValid)
        assertNull(model.newPasswordProblem)
        assertNull(model.confirmPasswordProblem)
    }

    @Test
    fun `the new password has to differ from the current one`() {
        val model = filledIn(current = "same secret", new = "same secret")

        assertFalse(model.formValid)
        assertEquals(ChangePasswordViewModel.SAME_PASSWORD, model.newPasswordProblem)
    }

    @Test
    fun `a new password over the server's limit is refused`() {
        val model = filledIn(new = "x".repeat(ChangePasswordViewModel.MAX_PASSWORD + 1))

        assertFalse(model.formValid)
        assertEquals("${ChangePasswordViewModel.MAX_PASSWORD} characters at most", model.newPasswordProblem)
    }

    @Test
    fun `the length is counted in characters, as the server counts them`() {
        // Python's len() counts code points; String.length counts UTF-16 units,
        // and each of these emoji is two of those.
        assertFalse(filledIn(new = "😀".repeat(4)).formValid)
        assertTrue(filledIn(new = "😀".repeat(8)).formValid)
    }

    @Test
    fun `a change sends both passwords, reports the server's answer and clears the form`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"message":"Your password has been changed."}"""))
        val model = filledIn()

        model.save()
        val status = model.status.first { it.done != null }

        assertEquals("Your password has been changed.", status.done)
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("old secret", body["current_password"]?.jsonPrimitive?.content)
        assertEquals("new secret", body["new_password"]?.jsonPrimitive?.content)
        assertEquals("", model.currentPassword)
        assertEquals("", model.newPassword)
        assertEquals("", model.confirmPassword)
    }

    @Test
    fun `a wrong current password is shown on that field and the rest is kept`() = runTest {
        server.enqueue(MockResponse(code = 400, body = """{"detail":"Your current password is incorrect."}"""))
        val model = filledIn()

        model.save()
        val status = model.status.first { !it.saving && it.currentPasswordError != null }

        assertEquals("Your current password is incorrect.", status.currentPasswordError)
        assertNull("not a banner as well", status.error)
        assertEquals("new secret", model.newPassword)
        assertEquals("new secret", model.confirmPassword)

        model.onCurrentPasswordChange("old secret!")
        assertNull("retyping clears it", model.status.value.currentPasswordError)
    }

    @Test
    fun `too many attempts says when to come back`() = runTest {
        server.enqueue(MockResponse(code = 429, body = """{"detail":"Too many requests, please try again later."}"""))
        val model = filledIn()

        model.save()

        assertEquals(ChangePasswordViewModel.RATE_LIMITED, model.status.first { it.error != null }.error)
    }

    @Test
    fun `any other refusal is a banner and keeps what was typed`() = runTest {
        server.enqueue(MockResponse(code = 409, body = """{"detail":"Your password was changed somewhere else meanwhile."}"""))
        val model = filledIn()

        model.save()

        assertEquals("Your password was changed somewhere else meanwhile.", model.status.first { it.error != null }.error)
        assertEquals("old secret", model.currentPassword)
        assertTrue("can try again", model.canSave)
    }

    @Test
    fun `an expired session sends the user back to sign in`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"detail":"Authentication required!"}"""))
        val model = filledIn()

        model.save()

        assertTrue(auth.sessionExpired.first { it })
        assertNull(tokens.token)
    }
}
