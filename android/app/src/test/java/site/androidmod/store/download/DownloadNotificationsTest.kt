package site.androidmod.store.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadNotificationsTest {

    private val key = DownloadKey("com.tranzmate", "5.201.0.1808")

    @Test
    fun `notifications name the app, not its package`() {
        // They used to read "Preparing com.tranzmate 5.201.0.1808".
        assertEquals("Moovit 5.201.0.1808", DownloadNotifications.label("Moovit", key))
    }

    @Test
    fun `an app the catalogue has not named falls back to its package`() {
        assertEquals("com.tranzmate 5.201.0.1808", DownloadNotifications.label(null, key))
        assertEquals("com.tranzmate 5.201.0.1808", DownloadNotifications.label(" ", key))
    }

    @Test
    fun `progress reads as a phase and an amount`() {
        assertEquals("Preparing...", DownloadNotifications.progressText(DownloadState.Queued))
        assertEquals("Preparing...", DownloadNotifications.progressText(DownloadState.Preparing(0.4, "Signing")))
        assertEquals(
            "Downloading 1.5 MB of 42 MB",
            DownloadNotifications.progressText(DownloadState.Transferring(3L * 512 * 1024, 42L * 1024 * 1024)),
        )
        assertEquals(
            "Downloading 512 B",
            DownloadNotifications.progressText(DownloadState.Transferring(512, -1)),
        )
    }

    @Test
    fun `a finished download says it is ready to install`() {
        val text = DownloadNotifications.finished("Moovit 5.201.0.1808", DownloadState.Done(File("x.xapk")))

        assertEquals("Moovit 5.201.0.1808 is ready to install", text?.title)
        assertEquals("Tap to open Patched Store and install it.", text?.body)
    }

    @Test
    fun `a failed download says why`() {
        val text = DownloadNotifications.finished(
            "Moovit 5.201.0.1808",
            DownloadState.Failed("The download was interrupted. Check your connection and try again."),
        )

        assertEquals("Moovit 5.201.0.1808 could not be downloaded", text?.title)
        assertEquals("The download was interrupted. Check your connection and try again.", text?.body)
    }

    @Test
    fun `an unchanged progress is not posted again`() {
        val throttle = ProgressThrottle(minIntervalMillis = 1_000)

        assertTrue(throttle.shouldPost(key, "Moovit|42", nowMillis = 0))
        assertFalse(throttle.shouldPost(key, "Moovit|42", nowMillis = 5_000))
    }

    @Test
    fun `updates are spaced out even when every one is new`() {
        // Android sheds a package's notification updates beyond about five a
        // second, and a shed update can be the one that mattered.
        val throttle = ProgressThrottle(minIntervalMillis = 1_000)

        assertTrue(throttle.shouldPost(key, "a|1", nowMillis = 0))
        assertFalse(throttle.shouldPost(key, "a|2", nowMillis = 400))
        assertTrue(throttle.shouldPost(key, "a|3", nowMillis = 1_000))
    }

    @Test
    fun `two downloads do not defeat the de-duplication`() {
        // One shared "last posted" used to see the two apps alternate and post
        // on nearly every 64 KB chunk of both.
        val other = DownloadKey("com.whatsapp", "2.26.33.76")
        val throttle = ProgressThrottle(minIntervalMillis = 0)

        assertTrue(throttle.shouldPost(key, "Moovit|10", nowMillis = 0))
        assertTrue(throttle.shouldPost(other, "WhatsApp|3", nowMillis = 1))
        assertFalse(throttle.shouldPost(key, "Moovit|10", nowMillis = 2))
        assertFalse(throttle.shouldPost(other, "WhatsApp|3", nowMillis = 3))
    }

    @Test
    fun `a download still running has no final notification`() {
        assertNull(DownloadNotifications.finished("Moovit 1.0", DownloadState.Preparing(0.5, null)))
    }
}
