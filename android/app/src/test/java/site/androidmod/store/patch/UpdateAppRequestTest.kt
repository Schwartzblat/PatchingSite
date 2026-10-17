package site.androidmod.store.patch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The UPDATE_APP intent is exported, so anything an outside app puts in it is checked here. */
class UpdateAppRequestTest {

    private val onDevice = setOf("com.whatsapp")

    @Test
    fun `an installed on-device package is accepted`() {
        assertTrue(isValidUpdateRequest("com.whatsapp", onDevice) { true })
    }

    @Test
    fun `null and junk are refused`() {
        assertFalse(isValidUpdateRequest(null, onDevice) { true })
        assertFalse(isValidUpdateRequest("", onDevice) { true })
        assertFalse(isValidUpdateRequest("whatsapp", setOf("whatsapp")) { true })
        assertFalse(isValidUpdateRequest("com.whatsapp; rm", setOf("com.whatsapp; rm")) { true })
        assertFalse(isValidUpdateRequest("../com.whatsapp", setOf("../com.whatsapp")) { true })
        assertFalse(isValidUpdateRequest("1com.whatsapp", setOf("1com.whatsapp")) { true })
    }

    @Test
    fun `a package that is not on-device is refused`() {
        assertFalse(isValidUpdateRequest("com.tranzmate", onDevice) { true })
    }

    @Test
    fun `a package that is not installed is refused`() {
        assertFalse(isValidUpdateRequest("com.whatsapp", onDevice) { false })
    }
}
