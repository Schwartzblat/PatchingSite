package site.androidmod.store.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootInstallerTest {

    @Test
    fun `reads the session id out of pm's output`() {
        // Verbatim from the device.
        assertEquals(
            2097227766,
            RootInstaller.parseSessionId("Success: created install session [2097227766]"),
        )
    }

    @Test
    fun `a missing session id is null rather than an exception`() {
        assertNull(RootInstaller.parseSessionId("Error: java.lang.SecurityException"))
        assertNull(RootInstaller.parseSessionId(""))
    }

    @Test
    fun `plain Success is a successful install`() {
        assertEquals(InstallOutcome.Success, RootInstaller.outcomeOf("Success"))
        assertEquals(InstallOutcome.Success, RootInstaller.outcomeOf("Success\n"))
    }

    @Test
    fun `a streamed-bytes line still counts as command success`() {
        // outcomeOf judges ONE pm command, and install-write legitimately reports
        // "Success: streamed ...". That a write is not mistaken for a finished
        // install is a property of install()'s orchestration - which withholds the
        // outcome until install-commit - not of this function, so do not read this
        // test as covering that.
        assertTrue(RootInstaller.outcomeOf("Success: streamed 34486700 bytes") is InstallOutcome.Success)
    }

    @Test
    fun `a failure is classified and explained`() {
        val outcome = RootInstaller.outcomeOf("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]")

        assertTrue(outcome is InstallOutcome.Failure)
        assertEquals(InstallFailure.SIGNATURE_CONFLICT, (outcome as InstallOutcome.Failure).reason)
        assertTrue(outcome.message.contains("Uninstall", ignoreCase = true))
    }

    @Test
    fun `unrecognised output is a failure, never a silent success`() {
        val outcome = RootInstaller.outcomeOf("su: permission denied")

        assertTrue(outcome is InstallOutcome.Failure)
        assertEquals(InstallFailure.UNKNOWN, (outcome as InstallOutcome.Failure).reason)
    }
}
