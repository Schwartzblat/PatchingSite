package site.androidmod.store.ui

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationRequestsTest {
    private val fromHistory = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY

    @Test
    fun `an update extra becomes an update request`() {
        assertEquals(NavRequest.Update("com.whatsapp"), requestFrom("com.whatsapp", false, 0))
    }

    @Test
    fun `the move extra becomes a move request`() {
        assertEquals(NavRequest.Move, requestFrom(null, true, 0))
    }

    @Test
    fun `the library extra opens the Library and nothing else`() {
        assertEquals(NavRequest.Library, requestFrom(null, false, 0, library = true))
        // An update request outranks it.
        assertEquals(NavRequest.Update("com.whatsapp"), requestFrom("com.whatsapp", false, 0, library = true))
    }

    @Test
    fun `the generic extra opens that app's run screen`() {
        assertEquals(NavRequest.Generic("com.example.app"), requestFrom(null, false, 0, generic = "com.example.app"))
        assertNull(requestFrom(null, false, fromHistory, generic = "com.example.app"))
    }

    @Test
    fun `no extras is no request`() {
        assertNull(requestFrom(null, false, 0))
    }

    @Test
    fun `a relaunch from Recents replays nothing`() {
        assertNull(requestFrom("com.whatsapp", false, fromHistory or Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNull(requestFrom(null, true, fromHistory))
        assertNull(requestFrom(null, false, fromHistory, library = true))
    }
}
