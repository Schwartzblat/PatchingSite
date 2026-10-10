package site.androidmod.store.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test
import site.androidmod.store.install.InstallErrors
import site.androidmod.store.install.InstallFailure
import site.androidmod.store.install.InstallOutcome

/**
 * The text of the toast raised when an install finishes. It has two lines to work
 * with (Android 12+ truncates after the second), so each case is pinned to exactly
 * what fits: the verdict, a short reason, and the installer's code when there is one.
 */
class InstallToastTest {

    @Test
    fun `a success names the app and version`() {
        assertEquals(
            "Installed WhatsApp 2.26.36.72",
            installToast("WhatsApp", "2.26.36.72", InstallOutcome.Success),
        )
    }

    @Test
    fun `a recognised failure gives the short reason and then the installer's code`() {
        val outcome = InstallErrors.failure("INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.whatsapp signatures do not match")
        assertEquals(
            "Install failed: signature conflict\nINSTALL_FAILED_UPDATE_INCOMPATIBLE",
            installToast("WhatsApp", "2.26.36.72", outcome),
        )
    }

    @Test
    fun `a user cancelling the system dialog reads as cancelled`() {
        val outcome = InstallErrors.failure("INSTALL_FAILED_ABORTED: User rejected permissions")
        assertEquals(
            "Install failed: cancelled\nINSTALL_FAILED_ABORTED",
            installToast("WhatsApp", "2.26.36.72", outcome),
        )
    }

    @Test
    fun `a failure raised before any installer ran has no code line`() {
        val outcome = InstallOutcome.Failure(
            "This build is signed by a different key than the store app, so it was not installed.",
            InstallFailure.UNTRUSTED_SIGNER,
        )
        assertEquals("Install failed: untrusted signature", installToast("WhatsApp", "2.26.36.72", outcome))
    }

    @Test
    fun `an unrecognised failure with a code shows the code as the reason`() {
        val outcome = InstallErrors.failure("INSTALL_FAILED_INVALID_APK: Split lib_x was defined multiple times")
        assertEquals(
            "Install failed\nINSTALL_FAILED_INVALID_APK",
            installToast("WhatsApp", "2.26.36.72", outcome),
        )
    }

    @Test
    fun `an unrecognised failure without a code falls back to its message`() {
        // Without this the toast would say it failed and never say why.
        val outcome = InstallOutcome.Failure("This build could not be read.", InstallFailure.UNKNOWN)
        assertEquals(
            "Install failed\nThis build could not be read.",
            installToast("WhatsApp", "2.26.36.72", outcome),
        )
    }
}
