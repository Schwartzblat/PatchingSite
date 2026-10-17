package site.androidmod.store.patch

import org.junit.Assert.assertEquals
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

    // --- where UpdateAppActivity sends a request (M10) ---

    @Test
    fun `an installed on-device package opens its update`() {
        assertEquals(UpdateAppRoute.UPDATE, updateAppRoute("com.whatsapp", onDevice) { true })
    }

    @Test
    fun `a real package the store doesn't update here still opens the Library`() {
        // Not on-device (the server hasn't switched it yet), or not installed: no update starts,
        // but the user who tapped the patched app's update button lands somewhere.
        assertEquals(UpdateAppRoute.OPEN_LIBRARY, updateAppRoute("com.tranzmate", onDevice) { true })
        assertEquals(UpdateAppRoute.OPEN_LIBRARY, updateAppRoute("com.whatsapp", onDevice) { false })
    }

    @Test
    fun `junk opens nothing`() {
        assertEquals(UpdateAppRoute.IGNORE, updateAppRoute(null, onDevice) { true })
        assertEquals(UpdateAppRoute.IGNORE, updateAppRoute("", onDevice) { true })
        assertEquals(UpdateAppRoute.IGNORE, updateAppRoute("whatsapp", setOf("whatsapp")) { true })
        assertEquals(UpdateAppRoute.IGNORE, updateAppRoute("com.whatsapp; rm", onDevice) { true })
        assertEquals(UpdateAppRoute.IGNORE, updateAppRoute("../com.whatsapp", onDevice) { true })
    }

    @Test
    fun `junk is never looked up`() {
        var asked = false
        updateAppRoute("com.whatsapp; rm", setOf("com.whatsapp; rm")) { asked = true; true }
        assertFalse(asked)
    }
}
