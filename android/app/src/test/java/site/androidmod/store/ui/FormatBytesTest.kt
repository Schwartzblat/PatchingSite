package site.androidmod.store.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import site.androidmod.store.data.NotEnoughSpaceException
import java.util.Locale

class FormatBytesTest {

    private val original = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(original)

    @Test
    fun `sizes read the same whatever the device locale`() {
        // The UI is English-only. A German default locale used to turn "1.5 MB"
        // into "1,5 MB" in the middle of English text.
        Locale.setDefault(Locale.GERMANY)

        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("2.5 MB", formatBytes(5L * 1024 * 1024 / 2))
    }

    @Test
    fun `a download that does not fit says how much to free`() {
        assertEquals(
            "There isn't enough free space for this download. Free up 1.5 MB and try again.",
            notEnoughSpaceMessage(NotEnoughSpaceException(3L * 512 * 1024)),
        )
        // ENOSPC mid-transfer from a server that sent no length: the amount is unknown.
        assertEquals(
            "There isn't enough free space for this download. Free up some space and try again.",
            notEnoughSpaceMessage(NotEnoughSpaceException(-1)),
        )
    }

    @Test
    fun `whole units drop the decimal`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("42 MB", formatBytes(42L * 1024 * 1024))
    }
}
