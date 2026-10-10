package site.androidmod.store.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallErrorsTest {

    @Test
    fun `recognises a signature conflict`() {
        assertEquals(
            InstallFailure.SIGNATURE_CONFLICT,
            InstallErrors.classify("INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.whatsapp signatures do not match"),
        )
        assertEquals(
            InstallFailure.SIGNATURE_CONFLICT,
            InstallErrors.classify("Failure [INSTALL_FAILED_SHARED_USER_INCOMPATIBLE]"),
        )
    }

    @Test
    fun `recognises the storage and downgrade cases`() {
        assertEquals(InstallFailure.STORAGE, InstallErrors.classify("INSTALL_FAILED_INSUFFICIENT_STORAGE"))
        assertEquals(InstallFailure.DOWNGRADE, InstallErrors.classify("INSTALL_FAILED_VERSION_DOWNGRADE"))
    }

    @Test
    fun `recognises a user abort`() {
        assertEquals(InstallFailure.ABORTED, InstallErrors.classify("INSTALL_FAILED_ABORTED: User rejected"))
    }

    @Test
    fun `anything else is unknown`() {
        assertEquals(InstallFailure.UNKNOWN, InstallErrors.classify("INSTALL_FAILED_INVALID_APK"))
        assertEquals(InstallFailure.UNKNOWN, InstallErrors.classify(null))
        assertEquals(InstallFailure.UNKNOWN, InstallErrors.classify(""))
    }

    @Test
    fun `a signature conflict is explained in terms of what to do about it`() {
        val message = InstallErrors.describe(InstallFailure.SIGNATURE_CONFLICT, null)
        assertTrue(message.contains("uninstall", ignoreCase = true))
        assertTrue(message.contains("data", ignoreCase = true))
    }

    @Test
    fun `extracts the installer's code from both installers' output`() {
        // PackageInstaller's EXTRA_STATUS_MESSAGE
        assertEquals(
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
            InstallErrors.code("INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.whatsapp signatures do not match"),
        )
        // pm, over su
        assertEquals(
            "INSTALL_FAILED_INSUFFICIENT_STORAGE",
            InstallErrors.code("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"),
        )
        assertEquals(
            "INSTALL_PARSE_FAILED_NO_CERTIFICATES",
            InstallErrors.code("Failure [INSTALL_PARSE_FAILED_NO_CERTIFICATES: no certs]"),
        )
    }

    @Test
    fun `output that names no code has none`() {
        assertNull(InstallErrors.code("su: permission denied"))
        assertNull(InstallErrors.code(""))
        assertNull(InstallErrors.code(null))
    }

    @Test
    fun `a failure built from installer output carries its reason, explanation and code`() {
        val failure = InstallErrors.failure("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]")
        assertEquals(InstallFailure.DOWNGRADE, failure.reason)
        assertEquals(InstallErrors.describe(InstallFailure.DOWNGRADE, null), failure.message)
        assertEquals("INSTALL_FAILED_VERSION_DOWNGRADE", failure.code)
    }

    @Test
    fun `every recognised failure has a short summary and unknown has none`() {
        InstallFailure.entries.filter { it != InstallFailure.UNKNOWN }.forEach {
            assertNotNull(it.name, InstallErrors.summarize(it))
        }
        assertNull(InstallErrors.summarize(InstallFailure.UNKNOWN))
    }

    @Test
    fun `an unknown failure still shows the raw reason so it can be debugged`() {
        val message = InstallErrors.describe(InstallFailure.UNKNOWN, "INSTALL_FAILED_INVALID_APK")
        assertTrue(message.contains("INSTALL_FAILED_INVALID_APK"))
    }
}
