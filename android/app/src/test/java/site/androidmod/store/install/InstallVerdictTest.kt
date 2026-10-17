package site.androidmod.store.install

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Waiting for PackageInstaller's verdict. The receiver side needs an Intent, so
 * these drive the registry it delivers through directly.
 */
class InstallVerdictTest {

    private val timeout = 60_000L

    @Test
    fun `a verdict that arrives in time is returned`() = runTest {
        var abandoned = 0
        val result = InstallReceiver.awaiting(SESSION)
        val verdict = async { InstallReceiver.await(SESSION, result, timeout) { abandoned++ } }

        assertTrue(InstallReceiver.deliver(SESSION, InstallOutcome.Success))

        assertEquals(InstallOutcome.Success, verdict.await())
        assertEquals(0, abandoned)
    }

    @Test
    fun `no verdict in time abandons the session and says what to do`() = runTest {
        // The user left the app while the build was staging, Android refused to
        // start the confirm dialog from the background, and nothing ever reported
        // back. The Install button said "Installing..." forever.
        var abandoned = 0
        val result = InstallReceiver.awaiting(SESSION)

        val verdict = InstallReceiver.await(SESSION, result, timeout) { abandoned++ }

        assertEquals(1, abandoned)
        val failure = verdict as InstallOutcome.Failure
        assertEquals(InstallFailure.TIMED_OUT, failure.reason)
        assertEquals("Installation didn't finish. Tap Install to try again.", failure.message)
    }

    @Test
    fun `a verdict that arrives after the timeout is dropped`() = runTest {
        val result = InstallReceiver.awaiting(SESSION)
        InstallReceiver.await(SESSION, result, timeout) { }

        assertFalse("nobody is waiting any more", InstallReceiver.deliver(SESSION, InstallOutcome.Success))
        assertFalse(
            "and no confirm dialog is started for a session that was abandoned",
            InstallReceiver.isPending(SESSION),
        )
    }

    @Test
    fun `the wait is not cut short before the timeout`() = runTest {
        val result = InstallReceiver.awaiting(SESSION)
        val verdict = async { InstallReceiver.await(SESSION, result, timeout) { } }

        advanceTimeBy(timeout - 1)
        assertTrue(InstallReceiver.isPending(SESSION))
        InstallReceiver.deliver(SESSION, InstallOutcome.Success)

        assertEquals(InstallOutcome.Success, verdict.await())
    }

    @Test
    fun `a cancelled wait abandons the session too`() = runTest {
        // The user left the app while the build was staging, so the view model's
        // scope was cancelled after the commit. Without this the session stayed
        // pending, and a late confirmation request opened a dialog nobody tracked.
        var abandoned = 0
        val result = InstallReceiver.awaiting(SESSION)
        val verdict = async { InstallReceiver.await(SESSION, result, timeout) { abandoned++ } }
        advanceTimeBy(1_000)

        verdict.cancel()
        verdict.join()

        assertEquals(1, abandoned)
        assertFalse(InstallReceiver.isPending(SESSION))
    }

    @Test
    fun `a timed-out install reads as unfinished, not as an unknown failure`() {
        assertEquals("didn't finish", InstallErrors.summarize(InstallFailure.TIMED_OUT))
    }

    // --- how Android's request for the user's go-ahead is answered ---

    @Test
    fun `by default an install only starts the dialog and waits three minutes`() {
        // The Library, the guide and the store's own update: unchanged.
        assertEquals(3 * 60 * 1000L, InstallConfirmation.DIALOG.timeoutMillis)
        assertNull(InstallConfirmation.DIALOG.notify)

        InstallReceiver.awaiting(SESSION)

        assertSame(InstallConfirmation.DIALOG, InstallReceiver.confirmationFor(SESSION))
        InstallReceiver.forget(SESSION)
    }

    @Test
    fun `the receiver answers a session the way its installer asked`() {
        val notifier: (android.content.Intent) -> Unit = { }
        val confirmation = InstallConfirmation(30 * 60 * 1000L, notifier)

        InstallReceiver.awaiting(SESSION, confirmation)

        assertSame(confirmation, InstallReceiver.confirmationFor(SESSION))
        assertSame(notifier, InstallReceiver.confirmationFor(SESSION)?.notify)
        assertTrue(InstallReceiver.deliver(SESSION, InstallOutcome.Success))
        assertNull("a finished session asks nobody", InstallReceiver.confirmationFor(SESSION))
    }

    @Test
    fun `a session nobody waits on gets neither a dialog nor a notification`() = runTest {
        assertNull(InstallReceiver.confirmationFor(SESSION + 1))

        val result = InstallReceiver.awaiting(SESSION, InstallConfirmation(timeout) { })
        InstallReceiver.await(SESSION, result, timeout) { }

        assertNull("abandoned at the timeout", InstallReceiver.confirmationFor(SESSION))
    }

    private companion object {
        const val SESSION = 4242
    }
}
