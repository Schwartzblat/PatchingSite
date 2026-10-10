package site.androidmod.store.move

import com.patcher.migrate.MoveRecord
import com.patcher.migrate.ParkManifest.State
import com.patcher.migrate.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Going back from ours to the official WhatsApp: the 2026-10-09 spec's table, row by row. */
class MoveGuideReturnTest {

    private fun atStep(step: GuideStep, variant: StepVariant = StepVariant.NORMAL) = MovePhase.AtStep(step, variant)

    /** The record park() writes when leaving ours, which is NEWEST. */
    private fun fromOurs(state: State = State.PARKED) =
        manifest(state, from = OURS_SIG, versionName = NEWEST, versionCode = 263974L)

    // --- the direction ---

    @Test
    fun `without access ours installed is asked for access on the way back`() {
        val g = guide(facts(access = false, signature = OURS_SIG, waFolder = false, newestBackupMs = 0))
        assertEquals(MoveDirection.TO_OFFICIAL, g.direction)
        assertEquals(atStep(GuideStep.ALLOW_ACCESS), g.phase)
        assertTrue(g.oursAtRisk)
    }

    @Test
    fun `without access a return under way asks for access back`() {
        val g = guide(
            facts(access = false, installed = false, waFolder = false, newestBackupMs = 0),
            moveActive = true, toOfficialHint = true,
        )
        assertEquals(MoveDirection.TO_OFFICIAL, g.direction)
        assertEquals(atStep(GuideStep.ALLOW_ACCESS, StepVariant.ACCESS_REVOKED), g.phase)
        assertTrue(g.inProgress)
        assertFalse(g.oursAtRisk)
    }

    @Test
    fun `a move begun before going back existed stays a move into ours`() {
        // Its hint was written with no direction. Ours is installed because that move
        // is at its last steps, and losing access must not turn it into a return.
        val g = guide(facts(access = false, signature = OURS_SIG, waFolder = false, newestBackupMs = 0), moveActive = true)
        assertEquals(MoveDirection.TO_OURS, g.direction)
        assertEquals(atStep(GuideStep.ALLOW_ACCESS, StepVariant.ACCESS_REVOKED), g.phase)
        assertFalse(g.oursAtRisk)
    }

    @Test
    fun `with access the manifest decides the direction whatever is installed`() {
        assertEquals(
            MoveDirection.TO_OFFICIAL,
            guide(facts(installed = false, waFolder = false, parkFolder = true, manifest = fromOurs())).direction,
        )
        assertEquals(
            MoveDirection.TO_OURS,
            guide(facts(signature = OURS_SIG, kept = true, manifest = manifest(State.RESTORED))).direction,
        )
    }

    @Test
    fun `the return has six steps and its own titles`() {
        val d = MoveDirection.TO_OFFICIAL
        assertEquals(6, d.steps.size)
        assertFalse(GuideStep.DOWNLOAD in d.steps)
        assertEquals(3, d.numberOf(GuideStep.REMOVE_OLD))
        assertEquals(4, d.numberOf(GuideStep.INSTALL_NEW))
        assertEquals("Remove our WhatsApp", GuideStep.REMOVE_OLD.title(d))
        assertEquals("Install WhatsApp from the Play Store", GuideStep.INSTALL_NEW.title(d))
        assertEquals("Remove the old WhatsApp", GuideStep.REMOVE_OLD.title(MoveDirection.TO_OURS))
        assertEquals(7, MoveDirection.TO_OURS.steps.size)
    }

    // --- before anything is parked ---

    @Test
    fun `a parked folder without a manifest needs attention on the way back too`() {
        val f = facts(signature = OURS_SIG, parkFolder = true)
        assertEquals(Step.NEEDS_ATTENTION, status(f).step)
        assertEquals(MovePhase.Attention, guide(f).phase)
    }

    @Test
    fun `the backup step works as it does going in`() {
        assertEquals(
            atStep(GuideStep.BACK_UP),
            guide(facts(signature = OURS_SIG, newestBackupMs = 0, newestBackupCrypt = "none")).phase,
        )
        val stale = facts(signature = OURS_SIG, newestBackupMs = STALE_BACKUP)
        assertEquals(atStep(GuideStep.BACK_UP), guide(stale).phase)
        assertEquals(atStep(GuideStep.REMOVE_OLD), guide(stale, acks = MoveAcks(oldBackup = true)).phase)
        val locked = facts(signature = OURS_SIG, newestBackupCrypt = "crypt15")
        assertEquals(atStep(GuideStep.BACK_UP), guide(locked).phase)
        assertEquals(atStep(GuideStep.REMOVE_OLD), guide(locked, acks = MoveAcks(password = true)).phase)
    }

    @Test
    fun `a fresh backup goes straight to removing ours with nothing to download`() {
        val g = guide(facts(signature = OURS_SIG))
        assertEquals(atStep(GuideStep.REMOVE_OLD), g.phase)
        assertTrue(g.isDone(GuideStep.BACK_UP))
        assertTrue(g.oursAtRisk)
        assertFalse(g.inProgress)
    }

    // --- parked, ours still installed ---

    @Test
    fun `parked with ours still installed only the removal is left`() {
        val f = facts(signature = OURS_SIG, waFolder = false, parkFolder = true, manifest = fromOurs())
        assertEquals(Step.UNINSTALL_WHATSAPP, status(f).step)
        val g = guide(f)
        assertEquals(atStep(GuideStep.REMOVE_OLD, StepVariant.BACKUP_SAVED), g.phase)
        assertTrue(g.inProgress)
        assertFalse(g.oursAtRisk)
    }

    @Test
    fun `ours removed with its data kept says how to undo it`() {
        val f = facts(signature = OURS_SIG, dataKept = true, waFolder = false, parkFolder = true, manifest = fromOurs())
        assertEquals(Step.UNINSTALL_WHATSAPP, status(f).step)
        val g = guide(f)
        assertEquals(MoveDirection.TO_OFFICIAL, g.direction)
        assertEquals(atStep(GuideStep.REMOVE_OLD, StepVariant.DATA_KEPT), g.phase)
    }

    @Test
    fun `an installed WhatsApp whose certificate cannot be read needs attention never a removal`() {
        val f = facts(signature = null, waFolder = false, parkFolder = true, manifest = fromOurs())
        assertEquals(Step.UNINSTALL_WHATSAPP, status(f).step)
        assertEquals(MovePhase.Attention, guide(f).phase)
    }

    // --- parked, ours removed ---

    @Test
    fun `ours removed the Play Store is next`() {
        val f = facts(installed = false, waFolder = false, parkFolder = true, manifest = fromOurs())
        assertEquals(Step.PUT_BACK, status(f).step)
        assertEquals(atStep(GuideStep.INSTALL_NEW), guide(f).phase)
    }

    @Test
    fun `the official app installed with the backup still parked offers the put-back`() {
        val newer = facts(versionName = "2.26.40.1", versionCode = 264001L, waFolder = false, parkFolder = true,
            manifest = fromOurs())
        assertEquals(Step.PUT_BACK, status(newer).step)
        assertEquals(atStep(GuideStep.INSTALL_NEW, StepVariant.PUT_BACK_NOW), guide(newer).phase)
        // WhatsApp reads a backup made by its own version, so "the same" is not "older".
        val same = facts(versionName = NEWEST, versionCode = 263974L, waFolder = false, parkFolder = true,
            manifest = fromOurs())
        assertEquals(atStep(GuideStep.INSTALL_NEW, StepVariant.PUT_BACK_NOW), guide(same).phase)
    }

    @Test
    fun `an official app older than the backup is told to wait and both versions are given`() {
        val f = facts(versionName = "2.26.38.80", versionCode = 263880L, waFolder = false, parkFolder = true,
            manifest = fromOurs())
        assertEquals(Step.PUT_BACK, status(f).step)
        val g = guide(f)
        assertEquals(atStep(GuideStep.INSTALL_NEW, StepVariant.PLAY_TOO_OLD), g.phase)
        assertEquals("2.26.38.80", g.installedVersion)
        assertEquals(NEWEST, g.savedVersion)
    }

    @Test
    fun `something in the WhatsApp folder before the put-back needs attention`() {
        val f = facts(versionName = "2.26.40.1", versionCode = 264001L, waFolder = true, parkFolder = true,
            manifest = fromOurs())
        assertEquals(Step.PUT_BACK, status(f).step)
        assertEquals(MovePhase.Attention, guide(f).phase)
    }

    // --- put back ---

    @Test
    fun `a backup already back with nothing installed still goes on to the Play Store`() {
        // Reached when ours was reinstalled from the store mid-return - the gate put
        // the backup back first - and that install failed.
        val f = facts(installed = false, waFolder = true, kept = true, manifest = fromOurs(State.RESTORED))
        assertEquals(Step.INSTALL_PATCHED, status(f).step)
        assertEquals(atStep(GuideStep.INSTALL_NEW), guide(f).phase)
    }

    @Test
    fun `a backup put back and then removed with WhatsApp goes back from the spare copy`() {
        // Before 2026-10-10 this needed attention, though Databases-kept still held the
        // backup. Now it is put back again when the Play Store installs WhatsApp.
        val f = facts(installed = false, waFolder = false, kept = true, manifest = fromOurs(State.RESTORED))
        assertEquals(Step.PUT_BACK, status(f).step)
        assertEquals(atStep(GuideStep.INSTALL_NEW), guide(f).phase)
    }

    @Test
    fun `ours reinstalled over the put-back backup needs attention`() {
        val f = facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L, waFolder = true,
            kept = true, manifest = fromOurs(State.RESTORED))
        assertEquals(Step.INSTALL_PATCHED, status(f).step)
        assertEquals(MovePhase.Attention, guide(f).phase)
    }

    @Test
    fun `the official app over the put-back backup is restored then finished`() {
        val f = facts(versionName = "2.26.40.1", versionCode = 264001L, kept = true, manifest = fromOurs(State.RESTORED))
        assertEquals(Step.VERIFY_AND_RESTORE, status(f).step)
        val g = guide(f)
        assertEquals(atStep(GuideStep.RESTORE), g.phase)
        assertEquals(NOW - 5 * 60_000L, g.savedBackupMs)
        val confirmed = facts(versionName = "2.26.40.1", versionCode = 264001L, kept = true,
            manifest = fromOurs(State.RESTORED), newestBackupMs = NOW - 5 * 60_000L,
            record = MoveRecord.fromManifest(fromOurs(State.RESTORED), 0L)
                .withRestored(NOW - 4 * 60_000L, MoveRecord.Source.PARKED)
                .withChatsConfirmed(NOW - 60_000L))
        assertEquals(atStep(GuideStep.FINISH, StepVariant.LAST_CHECK), guide(confirmed).phase)
    }

    @Test
    fun `an official app older than a backup already put back needs attention`() {
        val f = facts(versionName = "2.26.38.80", versionCode = 263880L, kept = true, manifest = fromOurs(State.RESTORED))
        assertEquals(Step.VERIFY_AND_RESTORE, status(f).step)
        assertEquals(MovePhase.Attention, guide(f).phase)
    }

    @Test
    fun `without the spare copy the return still waits for the user's word`() {
        val f = facts(versionName = "2.26.40.1", versionCode = 264001L, manifest = fromOurs(State.RESTORED))
        assertEquals(Step.VERIFY_AND_RESTORE, status(f).step)
        assertEquals(atStep(GuideStep.RESTORE), guide(f).phase)
    }

    @Test
    fun `steps before the current one are ticked by position`() {
        val f = facts(versionName = "2.26.40.1", versionCode = 264001L, kept = true, manifest = fromOurs(State.RESTORED))
        val g = guide(f)
        listOf(GuideStep.ALLOW_ACCESS, GuideStep.BACK_UP, GuideStep.REMOVE_OLD, GuideStep.INSTALL_NEW)
            .forEach { assertTrue(it.name, g.isDone(it)) }
        assertFalse(g.isDone(GuideStep.RESTORE))
        assertFalse(g.isDone(GuideStep.DOWNLOAD))
    }

    @Test
    fun `after a return the guide says all done in its words and the page offers ours again`() {
        // Spare copy removed: no manifest, the official app installed. On its own
        // that reads as the start of a move into ours, and the page should say so.
        val g = guide(facts(versionName = "2.26.40.1", versionCode = 264001L),
            acks = MoveAcks(finished = MoveDirection.TO_OFFICIAL))
        assertEquals(MoveDirection.TO_OFFICIAL, g.finished)
        assertEquals(MoveDirection.TO_OURS, g.direction)
        assertTrue(g.officialAtRisk)
    }

    // --- where going back is offered ---

    @Test
    fun `going back is offered while ours is installed and nothing moved or a return is under way`() {
        assertTrue(guide(facts(signature = OURS_SIG)).offersReturn)
        assertTrue(guide(facts(installed = false, waFolder = false, parkFolder = true, manifest = fromOurs())).offersReturn)
    }

    @Test
    fun `going back is not offered while a move into ours is unfinished`() {
        val f = facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L, kept = true,
            manifest = manifest(State.RESTORED))
        assertFalse(guide(f).offersReturn)
        assertFalse(guide(f).oursAtRisk)
    }

    @Test
    fun `going back is not offered with the official app or with no WhatsApp`() {
        assertFalse(guide(facts()).offersReturn)
        assertFalse(guide(facts(installed = false, waFolder = false, newestBackupMs = 0)).offersReturn)
    }
}
