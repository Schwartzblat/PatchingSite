package site.androidmod.store.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import site.androidmod.store.install.InstalledApp
import site.androidmod.store.install.VersionOrder
import site.androidmod.store.patch.UpdateOffer
import site.androidmod.store.patch.foregroundStartAllowed

class OnDeviceLibraryTest {

    private fun entry(offer: UpdateOffer, onDevice: Boolean = true) = LibraryEntry(
        packageName = "com.whatsapp",
        name = "WhatsApp",
        installed = InstalledApp("com.whatsapp", "2.26.1"),
        latest = "2.26.2",
        order = VersionOrder.UNKNOWN,
        onDevice = onDevice,
        offer = offer,
    )

    @Test
    fun `each offer maps to its button`() {
        assertEquals(LibraryAction.NONE, libraryAction(entry(UpdateOffer.None)))
        assertEquals(LibraryAction.UPDATE, libraryAction(entry(UpdateOffer.NewVersion("2.26.2"))))
        assertEquals(LibraryAction.UPDATE, libraryAction(entry(UpdateOffer.PatchUpdate(emptyList()))))
        assertEquals(LibraryAction.MOVE, libraryAction(entry(UpdateOffer.NeedsMove)))
    }

    @Test
    fun `an off-device entry never gets an on-device button`() {
        assertEquals(LibraryAction.NONE, libraryAction(entry(UpdateOffer.NewVersion("2.26.2"), onDevice = false)))
    }

    @Test
    fun `the buttons carry the agreed words`() {
        assertEquals("Update", libraryActionLabel(LibraryAction.UPDATE))
        assertEquals("Move to the new update system", libraryActionLabel(LibraryAction.MOVE))
    }

    @Test
    fun `an on-device update is available by offer, not by version order`() {
        assertTrue(entry(UpdateOffer.PatchUpdate(emptyList())).updateAvailable)
        assertTrue(entry(UpdateOffer.NewVersion("2.26.2")).updateAvailable)
        assertFalse(entry(UpdateOffer.None).updateAvailable)
        assertFalse(entry(UpdateOffer.NeedsMove).updateAvailable)
    }

    @Test
    fun `an up-to-date message uses the app name`() {
        assertEquals("WhatsApp is up to date", upToDateMessage("WhatsApp"))
        assertEquals("WhatsApp is up to date", noUpdateMessage("WhatsApp", sdkInt = 33))
    }

    @Test
    fun `on android 12 an update request says why there is none`() {
        assertEquals("Patching on this phone needs Android 13 or newer.", noUpdateMessage("WhatsApp", sdkInt = 32))
    }

    @Test
    fun `without the user's key an update request can't say it is up to date`() {
        assertEquals(
            "Couldn't check for an update. Try again later.",
            noUpdateMessage("WhatsApp", sdkInt = 34, keyKnown = false),
        )
        assertEquals("WhatsApp is up to date", noUpdateMessage("WhatsApp", sdkInt = 34, keyKnown = true))
    }

    @Test
    fun `Update is off while a chat move is under way`() {
        assertFalse(libraryActionEnabled(LibraryAction.UPDATE, moveInProgress = true))
        assertTrue(libraryActionEnabled(LibraryAction.UPDATE, moveInProgress = false))
        // The move button leads into the guide, which is where a move under way continues.
        assertTrue(libraryActionEnabled(LibraryAction.MOVE, moveInProgress = true))
    }

    @Test
    fun `a refused service start is told apart`() {
        assertTrue(foregroundStartAllowed { })
        assertFalse(foregroundStartAllowed { throw IllegalStateException("app is in background") })
        assertEquals("Open the app and tap Update again.", UPDATE_NOT_STARTED)
    }
}
