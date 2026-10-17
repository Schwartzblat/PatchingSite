package site.androidmod.store.patch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import site.androidmod.store.install.InstallConfirmation
import site.androidmod.store.install.InstallErrors
import site.androidmod.store.install.InstallFailure
import site.androidmod.store.install.InstallOutcome
import site.androidmod.store.install.InstallReceiver

/**
 * PatchService's install of a finished update: nobody may be watching, so Android's
 * confirmation request also becomes a "Tap to install" notification, and the wait is long
 * enough for the user to come back to it.
 */
class UpdateInstallTest {

    private val prompt: (android.content.Intent) -> Unit = { }

    @Test
    fun `an update asks with a notification and waits thirty minutes`() = runTest {
        var asked: InstallConfirmation? = null

        UpdateInstall.run(install = { asked = it; InstallOutcome.Success }, prompt = prompt, clearPrompt = { })

        assertEquals(30 * 60 * 1000L, asked?.timeoutMillis)
        assertSame(prompt, asked?.notify)
    }

    @Test
    fun `an installed update is no failure and clears the prompt`() = runTest {
        var cleared = 0

        val failure = UpdateInstall.run(install = { InstallOutcome.Success }, prompt = prompt, clearPrompt = { cleared++ })

        assertNull(failure)
        assertEquals(1, cleared)
    }

    @Test
    fun `a definitive failure keeps the installer's words`() = runTest {
        var cleared = 0
        val refused = InstallErrors.failure("INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match")

        val failure = UpdateInstall.run(install = { refused }, prompt = prompt, clearPrompt = { cleared++ })

        assertEquals(refused.message, failure)
        assertEquals(1, cleared)
    }

    @Test
    fun `an update nobody confirms gives up after thirty minutes`() = runTest {
        var cleared = 0
        var abandoned = 0
        val outcome = async {
            UpdateInstall.run(
                install = { confirmation ->
                    // The session as SessionInstaller waits on it.
                    val result = InstallReceiver.awaiting(SESSION, confirmation)
                    InstallReceiver.await(SESSION, result, confirmation.timeoutMillis) { abandoned++ }
                },
                prompt = prompt,
                clearPrompt = { cleared++ },
            )
        }

        // Well past the three minutes a Library install gets: still waiting, prompt still up.
        advanceTimeBy(30 * 60 * 1000L - 1)
        runCurrent()
        assertTrue(InstallReceiver.isPending(SESSION))
        assertSame(prompt, InstallReceiver.confirmationFor(SESSION)?.notify)
        assertEquals(0, cleared)

        assertEquals("The update wasn't confirmed. Tap Update to try again.", outcome.await())
        assertEquals(1, abandoned)
        assertEquals(1, cleared)
        assertFalse(InstallReceiver.isPending(SESSION))
    }

    @Test
    fun `a confirmation in time installs`() = runTest {
        val outcome = async {
            UpdateInstall.run(
                install = { confirmation ->
                    val result = InstallReceiver.awaiting(SESSION, confirmation)
                    InstallReceiver.await(SESSION, result, confirmation.timeoutMillis) { }
                },
                prompt = prompt,
                clearPrompt = { },
            )
        }
        advanceTimeBy(20 * 60 * 1000L)
        runCurrent()

        assertTrue(InstallReceiver.deliver(SESSION, InstallOutcome.Success))
        assertNull(outcome.await())
    }

    @Test
    fun `a cancelled install still clears the prompt`() = runTest {
        var cleared = 0
        try {
            UpdateInstall.run(install = { throw CancellationException("service stopped") }, prompt = prompt, clearPrompt = { cleared++ })
            fail("the cancellation must propagate")
        } catch (expected: CancellationException) {
            // Teardown, not a failure.
        }
        assertEquals(1, cleared)
    }

    @Test
    fun `only a timeout reads as unconfirmed`() {
        assertEquals(
            "The update wasn't confirmed. Tap Update to try again.",
            UpdateInstall.failureMessage(InstallErrors.timedOut()),
        )
        assertEquals(
            "The install was cancelled.",
            UpdateInstall.failureMessage(InstallOutcome.Failure("The install was cancelled.", InstallFailure.ABORTED)),
        )
        assertNull(UpdateInstall.failureMessage(InstallOutcome.Success))
    }

    private companion object {
        const val SESSION = 5151
    }
}
