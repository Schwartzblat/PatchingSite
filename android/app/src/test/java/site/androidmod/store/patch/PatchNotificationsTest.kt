package site.androidmod.store.patch

import org.junit.Assert.assertEquals
import org.junit.Test
import site.androidmod.store.download.NotificationText

class PatchNotificationsTest {

    @Test
    fun `stage texts`() {
        assertEquals("Checking version…", PatchNotifications.progressText("WhatsApp", PatchStage.CheckingVersion))
        assertEquals("Patching…", PatchNotifications.progressText("WhatsApp", PatchStage.Patching))
        assertEquals("Signing…", PatchNotifications.progressText("WhatsApp", PatchStage.Signing))
    }

    @Test
    fun `download shows a percent`() {
        assertEquals(
            "Downloading WhatsApp 42%",
            PatchNotifications.progressText("WhatsApp", PatchStage.Downloading(42, 100)),
        )
        assertEquals(
            "Downloading WhatsApp 100%",
            PatchNotifications.progressText("WhatsApp", PatchStage.Downloading(150, 100)),
        )
    }

    @Test
    fun `download without a total has no percent`() {
        assertEquals(
            "Downloading WhatsApp…",
            PatchNotifications.progressText("WhatsApp", PatchStage.Downloading(42, -1)),
        )
        assertEquals(
            "Downloading WhatsApp…",
            PatchNotifications.progressText("WhatsApp", PatchStage.Downloading(0, 0)),
        )
    }

    @Test
    fun `confirm text`() {
        assertEquals(
            NotificationText("Some features are off in this version", "Open the app to continue or cancel."),
            PatchNotifications.confirmText("WhatsApp"),
        )
    }
}
