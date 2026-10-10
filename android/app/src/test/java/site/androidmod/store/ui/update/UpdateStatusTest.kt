package site.androidmod.store.ui.update

import org.junit.Assert.assertEquals
import org.junit.Test
import site.androidmod.store.update.InstalledBuild
import site.androidmod.store.update.UpdateCheck
import site.androidmod.store.update.UpdatePhase
import site.androidmod.store.update.UpdateState

class UpdateStatusTest {

    private val base = UpdateState(installed = InstalledBuild(389000, "1.0"))

    @Test
    fun `a failed re-check is reported even though the earlier answer is kept`() {
        // The view model keeps `check` across a failed re-check so the Apps-tab
        // banner does not vanish on a flaky network. Settings must still say the
        // check the user just asked for failed, not repeat the stale answer.
        val state = base.copy(check = UpdateCheck.UpToDate, checkError = "No connection")

        assertEquals("Could not check for updates: No connection", updateStatus(state))
    }

    @Test
    fun `an available update names the build and its size`() {
        val state = base.copy(check = UpdateCheck.Available(389001, "1.1", 28025397))

        assertEquals("Update available: 1.1 (build 389001) · 26 MB", updateStatus(state))
    }

    @Test
    fun `checking wins over the previous answer`() {
        val state = base.copy(check = UpdateCheck.UpToDate, phase = UpdatePhase.Checking)

        assertEquals("Checking for updates...", updateStatus(state))
    }

    @Test
    fun `up to date and nothing to compare read differently`() {
        assertEquals("Up to date", updateStatus(base.copy(check = UpdateCheck.UpToDate)))
        assertEquals(
            "The server has no build to compare against.",
            updateStatus(base.copy(check = UpdateCheck.Unknown)),
        )
    }
}
