package site.androidmod.store.move

import com.patcher.migrate.MoveRecord
import com.patcher.migrate.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancel at any step but Finish up (2026-10-24). Found on the owner's Pixel: the
 * uninstall dialog was declined after the park, WhatsApp kept running, and the guide
 * had no way back, while still offering a removal that would have lost two days.
 */
class MoveGuideCancelTest {

    private fun atStep(step: GuideStep, variant: StepVariant = StepVariant.NORMAL) = MovePhase.AtStep(step, variant)

    /** The park of the saved backup itself, as step 4 makes it. */
    private val parkedManifest = manifest(backupMs = FRESH_BACKUP)

    /** Parked from the official WhatsApp, which is still installed. */
    private val parked = facts(waFolder = false, parkFolder = true, manifest = parkedManifest, record = record())

    /** The same, after WhatsApp made a new folder: the owner's phone. */
    private val usedSince = facts(waFolder = true, parkFolder = true, manifest = parkedManifest, record = record())

    // --- WhatsApp used after the park ---

    @Test
    fun `WhatsApp used after the park is never told to remove it`() {
        assertEquals(Step.UNINSTALL_WHATSAPP, status(usedSince).step)
        val g = guide(usedSince)
        assertEquals(atStep(GuideStep.REMOVE_OLD, StepVariant.USED_SINCE_PARK), g.phase)
        assertTrue(g.canCancel)
        assertEquals(parkedManifest.parkedAtMs, g.parkedAtMs)
    }

    @Test
    fun `going back the same holds`() {
        val f = facts(
            signature = OURS_SIG, waFolder = true, parkFolder = true,
            manifest = manifest(from = OURS_SIG, versionName = NEWEST, versionCode = 263974L),
        )
        val g = guide(f)
        assertEquals(MoveDirection.TO_OFFICIAL, g.direction)
        assertEquals(atStep(GuideStep.REMOVE_OLD, StepVariant.USED_SINCE_PARK), g.phase)
        assertTrue(g.canCancel)
    }

    @Test
    fun `kept data still comes first`() {
        val f = facts(dataKept = true, waFolder = true, parkFolder = true, manifest = manifest())
        assertEquals(atStep(GuideStep.REMOVE_OLD, StepVariant.DATA_KEPT), guide(f).phase)
        assertFalse(guide(f).canCancel)
    }

    @Test
    fun `a park left standing for over an hour counts as used, even with WhatsApp's folder still empty`() {
        // Messages go into WhatsApp's own database, not its folder.
        val old = facts(
            waFolder = false, parkFolder = true, record = record(),
            manifest = manifest(backupMs = FRESH_BACKUP, parkedAt = NOW - 2 * 60 * 60_000L),
        )
        assertEquals(atStep(GuideStep.REMOVE_OLD, StepVariant.USED_SINCE_PARK), guide(old).phase)
        assertTrue(guide(old).canCancel)
    }

    @Test
    fun `a WhatsApp installed again after the park keeps the removal, as before`() {
        val f = facts(
            waFolder = true, parkFolder = true, manifest = parkedManifest, record = record(),
            firstInstall = NOW - 60_000L,
        )
        assertEquals(atStep(GuideStep.REMOVE_OLD, StepVariant.BACKUP_SAVED), guide(f).phase)
        assertFalse(guide(f).canCancel)
    }

    @Test
    fun `a cancel stopped part-way is finished from the same step`() {
        val f = facts(waFolder = true, parkFolder = false, manifest = parkedManifest, record = record())
        assertEquals(Step.UNINSTALL_WHATSAPP, status(f).step)
        assertEquals(atStep(GuideStep.REMOVE_OLD, StepVariant.USED_SINCE_PARK), guide(f).phase)
        assertTrue(guide(f).canCancel)
    }

    // --- where Cancel is offered ---

    @Test
    fun `parked with the uninstall declined offers cancel`() {
        val g = guide(parked)
        assertEquals(atStep(GuideStep.REMOVE_OLD, StepVariant.BACKUP_SAVED), g.phase)
        assertTrue(g.canCancel)
    }

    @Test
    fun `with only a Start cancel is offered and needs nothing from the engine`() {
        assertFalse(status(facts()).canCancel)
        assertTrue(guide(facts()).canCancel)
        assertFalse(guide(facts(), startedAt = 0L).canCancel)
    }

    @Test
    fun `without file access only a move not yet under way can be cancelled`() {
        val f = facts(access = false)
        assertEquals(atStep(GuideStep.ALLOW_ACCESS), guide(f).phase)
        assertTrue(guide(f).canCancel)
        assertEquals(
            atStep(GuideStep.ALLOW_ACCESS, StepVariant.ACCESS_REVOKED),
            guide(f, moveActive = true).phase,
        )
        assertFalse(guide(f, moveActive = true).canCancel)
    }

    @Test
    fun `after the removal cancel is offered until Finish up`() {
        val removed = facts(installed = false, waFolder = false, parkFolder = true, manifest = parkedManifest, record = record())
        assertEquals(atStep(GuideStep.INSTALL_NEW), guide(removed, downloaded = true).phase)
        assertTrue(guide(removed, downloaded = true).canCancel)

        val restoring = facts(
            signature = OURS_SIG,
            record = record(restoredAt = NOW - 60_000L, restoredFrom = MoveRecord.Source.SAVED),
            newestBackupMs = NOW - 60_000L,
        )
        assertEquals(atStep(GuideStep.RESTORE), guide(restoring, moveActive = true).phase)
        assertTrue(guide(restoring, moveActive = true).canCancel)

        val lastCheck = facts(
            signature = OURS_SIG,
            record = record(restoredAt = NOW - 60_000L, restoredFrom = MoveRecord.Source.SAVED, confirmedAt = NOW),
            newestBackupMs = NOW - 60_000L,
        )
        assertEquals(atStep(GuideStep.FINISH, StepVariant.LAST_CHECK), guide(lastCheck, moveActive = true).phase)
        assertFalse(guide(lastCheck, moveActive = true).canCancel)
    }

    @Test
    fun `a move that needs attention offers no cancel`() {
        val f = facts(signature = null, waFolder = false, parkFolder = true, manifest = manifest())
        assertEquals(MovePhase.Attention, guide(f).phase)
        assertFalse(guide(f).canCancel)
    }

    // --- after a cancel ---

    @Test
    fun `a cancel says what became of the backup until a move starts again`() {
        val cancelled = MoveAcks(cancelled = CancelOutcome.CHATS_STAY)
        assertEquals(CancelOutcome.CHATS_STAY, guide(facts(), acks = cancelled).cancelled)
        assertNull(guide(parked, acks = cancelled).cancelled)
    }

    @Test
    fun `the outcome is read from where the move stood`() {
        assertEquals(CancelOutcome.CHATS_STAY, cancelOutcome(status(facts(record = record()))))
        assertEquals(CancelOutcome.CHATS_STAY, cancelOutcome(status(parked)))
        assertEquals(CancelOutcome.CHATS_STAY, cancelOutcome(status(usedSince)))
        val removed = facts(installed = false, waFolder = false, parkFolder = true, manifest = manifest())
        assertEquals(CancelOutcome.BACKUP_BACK, cancelOutcome(status(removed)))
        val restoring = facts(
            signature = OURS_SIG,
            record = record(restoredAt = NOW - 60_000L, restoredFrom = MoveRecord.Source.SAVED),
        )
        assertEquals(CancelOutcome.BACKUP_IN_NEW, cancelOutcome(status(restoring)))
    }
}
