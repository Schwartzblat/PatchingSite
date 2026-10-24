package site.androidmod.store.move

import com.patcher.migrate.MoveRecord
import com.patcher.migrate.ParkManifest.State
import com.patcher.migrate.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide over the 2026-10-10 spec's new states: a saved copy before step 4,
 * WhatsApp removed by hand, the last check before finishing, and a backup
 * deleted before it could be saved.
 */
class MoveGuideSavedCopyTest {

    private fun atStep(step: GuideStep, variant: StepVariant = StepVariant.NORMAL) = MovePhase.AtStep(step, variant)

    /** Leaving ours (NEWEST), on the way back. */
    private fun fromOurs() = record(from = OURS_SIG, versionName = NEWEST, versionCode = 263974L)

    /** Put back from the saved copy a minute ago, into ours. */
    private fun putBack(confirmedAt: Long = 0L, evidenceAt: Long = 0L, evidenceSize: Long = 0L) = record(
        restoredAt = NOW - 60_000L, restoredFrom = MoveRecord.Source.SAVED,
        confirmedAt = confirmedAt, evidenceAt = evidenceAt, evidenceSize = evidenceSize,
    )

    // --- before step 4 ---

    @Test
    fun `a saved copy is a move under way, and WhatsApp is still at risk until step 4`() {
        val f = facts(record = record())
        assertEquals(Step.BACK_UP_AND_PARK, status(f).step)
        val g = guide(f)
        assertTrue(g.inProgress)
        assertTrue(g.officialAtRisk)
        assertEquals(SavedBackup(FRESH_BACKUP, 1_000_000L), g.saved)
        assertTrue(g.canCancel)
        assertEquals(atStep(GuideStep.DOWNLOAD), g.phase)
    }

    @Test
    fun `steps 2 and 3 still tick on their own evidence while only the copy exists`() {
        val g = guide(facts(record = record()))
        assertTrue(g.isDone(GuideStep.BACK_UP))
        assertFalse(g.isDone(GuideStep.DOWNLOAD))
    }

    @Test
    fun `a copy that no longer matches its record is not called saved`() {
        assertNull(guide(facts(record = record(), savedMatches = false)).saved)
    }

    @Test
    fun `a WhatsApp installed again during the move is flagged and cannot be cancelled`() {
        val g = guide(facts(record = record(), firstInstall = FIRST_INSTALL + 1))
        assertTrue(g.reinstalled)
        assertFalse(g.canCancel)
    }

    @Test
    fun `cancel is offered where the engine allows it, and with only a Start`() {
        assertTrue(guide(facts(record = record())).canCancel)
        // Nothing on disk: cancelling forgets the Start (MoveGuideCancelTest).
        assertTrue(guide(facts()).canCancel)
        assertFalse(guide(facts(), startedAt = 0L).canCancel)
        // Since 2026-10-24 a park can be cancelled too.
        val parked = facts(waFolder = false, parkFolder = true, manifest = manifest(backupMs = FRESH_BACKUP),
            record = record())
        assertEquals(Step.UNINSTALL_WHATSAPP, status(parked).step)
        assertTrue(guide(parked).canCancel)
    }

    @Test
    fun `the direction comes from the record before anything is parked`() {
        val g = guide(facts(signature = OURS_SIG, record = fromOurs()))
        assertEquals(MoveDirection.TO_OFFICIAL, g.direction)
        assertTrue(g.oursAtRisk)
        assertTrue(g.offersReturn)
    }

    // --- WhatsApp removed by hand before step 4 ---

    @Test
    fun `WhatsApp removed by hand goes on to the install, with the media warning`() {
        val f = facts(installed = false, waFolder = false, newestBackupMs = 0, record = record())
        assertEquals(Step.PUT_BACK, status(f).step)
        assertEquals(atStep(GuideStep.INSTALL_NEW, StepVariant.NEEDS_DOWNLOAD), guide(f).phase)
        val g = guide(f, downloaded = true)
        assertEquals(atStep(GuideStep.INSTALL_NEW), g.phase)
        assertEquals(MoveDirection.TO_OURS, g.direction)
        assertTrue(g.mediaLost)
        assertTrue(g.inProgress)
    }

    @Test
    fun `ours removed by hand on the way back goes on to the Play Store, with the media warning`() {
        val g = guide(facts(installed = false, waFolder = false, newestBackupMs = 0, record = fromOurs()))
        assertEquals(MoveDirection.TO_OFFICIAL, g.direction)
        assertEquals(atStep(GuideStep.INSTALL_NEW), g.phase)
        assertTrue(g.mediaLost)
    }

    @Test
    fun `the Play Store's WhatsApp gets the saved copy when it can read it, and waits when it can't`() {
        val newer = facts(versionName = "2.26.40.1", versionCode = 264001L, waFolder = false,
            newestBackupMs = 0, record = fromOurs())
        assertEquals(atStep(GuideStep.INSTALL_NEW, StepVariant.PUT_BACK_NOW), guide(newer).phase)
        val older = facts(versionName = "2.26.38.80", versionCode = 263880L, waFolder = false,
            newestBackupMs = 0, record = fromOurs())
        assertEquals(atStep(GuideStep.INSTALL_NEW, StepVariant.PLAY_TOO_OLD), guide(older).phase)
        assertEquals(NEWEST, guide(older).savedVersion)
    }

    // --- the last check ---

    @Test
    fun `restore waits for the user's word, then for the last check`() {
        val before = facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L, record = putBack())
        assertEquals(Step.VERIFY_AND_RESTORE, status(before).step)
        assertEquals(atStep(GuideStep.RESTORE), guide(before).phase)

        val g = guide(facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L,
            record = putBack(confirmedAt = NOW - 30_000L)))
        assertEquals(atStep(GuideStep.FINISH, StepVariant.LAST_CHECK), g.phase)
        assertEquals(1_000_000L, g.putBackSize)
        assertTrue(g.mediaLost)
    }

    @Test
    fun `a much smaller new backup stops the finish`() {
        val g = guide(facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L,
            newestBackupMs = NOW - 10_000L, newestBackupSize = 200_000L,
            record = putBack(confirmedAt = NOW - 30_000L)))
        assertEquals(atStep(GuideStep.FINISH, StepVariant.BACKUP_TOO_SMALL), g.phase)
        assertEquals(200_000L, g.newBackupSize)
        assertEquals(1_000_000L, g.putBackSize)
    }

    @Test
    fun `the user's word and the proof finish the move`() {
        val f = facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L,
            newestBackupMs = NOW - 10_000L, newestBackupSize = 990_000L,
            record = putBack(confirmedAt = NOW - 30_000L, evidenceAt = NOW - 10_000L, evidenceSize = 990_000L))
        assertEquals(Step.DONE, status(f).step)
        val g = guide(f)
        assertEquals(atStep(GuideStep.FINISH), g.phase)
        assertEquals(990_000L, g.newBackupSize)
    }

    @Test
    fun `a move put back before records existed still asks for the user's word`() {
        // Review Focus 1: no record, a RESTORED manifest and its kept copy.
        val f = facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L, kept = true,
            manifest = manifest(State.RESTORED))
        val g = guide(f)
        assertEquals(atStep(GuideStep.RESTORE), g.phase)
        assertFalse(g.mediaLost)
    }

    // --- the backup deleted before it could be saved ---

    @Test
    fun `WhatsApp removed before its backup was saved is said so, within a day of Start`() {
        val f = facts(installed = false, waFolder = false, newestBackupMs = 0)
        assertEquals(Step.NOTHING_TO_MIGRATE, status(f).step)
        assertEquals(MovePhase.RemovedBeforeSaved, guide(f, seenInstalledAt = STARTED_AT + 1).phase)
    }

    @Test
    fun `nobody is told their backup was deleted unless WhatsApp was seen after Start`() {
        val f = facts(installed = false, waFolder = false, newestBackupMs = 0)
        assertEquals(MovePhase.NothingToDo, guide(f).phase)
        assertEquals(MovePhase.NothingToDo, guide(f, seenInstalledAt = STARTED_AT - 1).phase)
    }

    @Test
    fun `nor a day after Start`() {
        val f = facts(installed = false, waFolder = false, newestBackupMs = 0)
        val longAgo = NOW - MOVE_START_WINDOW_MILLIS - 1
        assertEquals(MovePhase.NothingToDo, guide(f, startedAt = longAgo, seenInstalledAt = longAgo + 1).phase)
    }
}
