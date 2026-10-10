package site.androidmod.store.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorTest {

    @Test
    fun `reads a plain string detail`() {
        val message = ApiErrors.messageFrom("""{"detail":"Subscription required"}""", 402)
        assertEquals("Subscription required", message)
    }

    @Test
    fun `reads a validation detail list and names the offending field`() {
        val body = """
            {"detail":[{"loc":["body","username"],"msg":"too short","type":"value_error"}]}
        """.trimIndent()
        assertEquals("username: too short", ApiErrors.messageFrom(body, 422))
    }

    @Test
    fun `joins multiple validation errors`() {
        val body = """
            {"detail":[
              {"loc":["body","username"],"msg":"too short","type":"value_error"},
              {"loc":["body","password"],"msg":"too weak","type":"value_error"}
            ]}
        """.trimIndent()
        assertEquals("username: too short, password: too weak", ApiErrors.messageFrom(body, 422))
    }

    @Test
    fun `falls back to msg when loc is missing`() {
        val body = """{"detail":[{"msg":"malformed request"}]}"""
        assertEquals("malformed request", ApiErrors.messageFrom(body, 422))
    }

    @Test
    fun `reads a message field when there is no detail`() {
        assertEquals("nope", ApiErrors.messageFrom("""{"message":"nope"}""", 400))
    }

    @Test
    fun `status zero means the network failed`() {
        assertEquals(
            "Network error. Check your connection and try again.",
            ApiErrors.messageFrom(null, 0),
        )
    }

    @Test
    fun `unparseable bodies degrade to the status code`() {
        assertEquals("Request failed (500)", ApiErrors.messageFrom("<html>oops</html>", 500))
        assertEquals("Request failed (503)", ApiErrors.messageFrom(null, 503))
        assertEquals("Request failed (400)", ApiErrors.messageFrom("", 400))
    }

    @Test
    fun `an empty detail list does not produce an empty message`() {
        assertEquals("Request failed (422)", ApiErrors.messageFrom("""{"detail":[]}""", 422))
    }

    @Test
    fun `uses a numeric loc segment as the field label`() {
        // Pydantic reports an error on a list element as loc ["body","tags",1].
        val body = """{"detail":[{"loc":["body","tags",1],"msg":"too short"}]}"""
        assertEquals("1: too short", ApiErrors.messageFrom(body, 422))
    }

    @Test
    fun `index zero is a usable label, unlike in the JS original`() {
        val body = """{"detail":[{"loc":["body","tags",0],"msg":"too short"}]}"""
        assertEquals("0: too short", ApiErrors.messageFrom(body, 422))
    }

    @Test
    fun `a JSON null never renders as the literal string null`() {
        assertEquals("Request failed (400)", ApiErrors.messageFrom("""{"detail":null}""", 400))
        assertEquals("Request failed (400)", ApiErrors.messageFrom("""{"message":null}""", 400))
    }

    @Test
    fun `a detail array whose items carry no message falls back to the status line`() {
        // api.js would return an empty string here, showing a blank error.
        // This port deliberately does not - see fromValidationErrors.
        val body = """{"detail":[{"type":"value_error"}]}"""
        assertEquals("Request failed (422)", ApiErrors.messageFrom(body, 422))
    }

    @Test
    fun `auth and subscription failures are distinguishable`() {
        assertTrue(ApiException(401, "expired").isAuth)
        assertFalse(ApiException(401, "expired").needsSubscription)
        assertTrue(ApiException(402, "subscribe").needsSubscription)
        assertFalse(ApiException(500, "boom").isAuth)
    }
}
