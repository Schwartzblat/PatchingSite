package site.androidmod.store.ui.generic

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import site.androidmod.store.generic.InstalledPackage

class ConfirmPatchTest {

    private val app = InstalledPackage("com.example.app", "Example", "1.0", 1L, listOf(File("/data/app/base.apk")), setOf("aa"))

    @Test
    fun a_started_patch_opens_the_run_screen() = runTest {
        var asked = false
        assertNull(confirmMessage(app) { asked = true; true })
        assertTrue(asked)
        // Not started by Android: the record is there, and the run screen offers Continue.
        assertNull(confirmMessage(app) { false })
    }

    @Test
    fun a_refusal_shows_its_own_words() = runTest {
        assertEquals(
            "Example is already being patched or put back.",
            confirmMessage(app) { throw IllegalStateException("Example is already being patched or put back.") },
        )
    }

    @Test
    fun a_failed_write_shows_a_message_instead_of_crashing() = runTest {
        assertEquals("Patching couldn't start. Try again.", confirmMessage(app) { throw IOException("No space left on device") })
        assertEquals("Patching couldn't start. Try again.", confirmMessage(app) { throw IllegalArgumentException("bad") })
    }

    @Test
    fun cancellation_still_propagates() {
        // CancellationException is an IllegalStateException on the JVM: it must not read as a refusal.
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { confirmMessage(app) { throw CancellationException("left the screen") } }
        }
    }
}
