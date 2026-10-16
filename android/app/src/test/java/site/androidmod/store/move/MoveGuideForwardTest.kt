package site.androidmod.store.move

import com.patcher.migrate.MoveRecord
import com.patcher.migrate.ParkManifest.State
import com.patcher.migrate.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The move into ours, row by row as the 2026-10-04 spec's table has it. Written
 * against the live code before going back was added, so that change cannot move
 * any of this unnoticed.
 */
class MoveGuideForwardTest {

    private fun atStep(step: GuideStep, variant: StepVariant = StepVariant.NORMAL) = MovePhase.AtStep(step, variant)

    /** Parked from the official app, which is still installed. */
    private val parked = facts(waFolder = false, parkFolder = true, manifest = manifest())

    // --- without file access ---

    @Test
    fun `without access the official app is asked for access`() {
        val g = guide(facts(access = false, waFolder = false, newestBackupMs = 0))
        assertEquals(atStep(GuideStep.ALLOW_ACCESS), g.phase)
        assertTrue(g.officialAtRisk)
        assertFalse(g.inProgress)
    }

    @Test
    fun `without access a move under way asks for access back`() {
        val g = guide(facts(access = false, installed = false, waFolder = false, newestBackupMs = 0), moveActive = true)
        assertEquals(atStep(GuideStep.ALLOW_ACCESS, StepVariant.ACCESS_REVOKED), g.phase)
        assertTrue(g.inProgress)
    }

    @Test
    fun `without access and without WhatsApp there is nothing to do`() {
        val g = guide(facts(access = false, installed = false, waFolder = false, newestBackupMs = 0))
        assertEquals(MovePhase.NothingToDo, g.phase)
    }

    @Test
    fun `without access an official app newer than ours is not possible yet`() {
        val g = guide(facts(access = false, versionName = "2.26.40.1", waFolder = false, newestBackupMs = 0))
        assertEquals(MovePhase.NotPossibleYet("2.26.40.1", NEWEST), g.phase)
    }

    // --- before anything is parked ---

    @Test
    fun `a parked folder without a manifest needs attention`() {
        val f = facts(parkFolder = true)
        assertEquals(Step.NEEDS_ATTENTION, status(f).step)
        assertEquals(MovePhase.Attention, guide(f).phase)
    }

    @Test
    fun `our own WhatsApp is where the way back starts not a move in`() {
        // Until 2026-10-09 this was "nothing to move". Ours installed now reads as
        // the start of going back; MoveGuideReturnTest covers that direction.
        val g = guide(facts(signature = OURS_SIG))
        assertEquals(MoveDirection.TO_OFFICIAL, g.direction)
        assertFalse(g.officialAtRisk)
    }

    @Test
    fun `a move into ours under way keeps its direction with access whatever is installed`() {
        // Its stored direction decides, not the build installed now: ours over a park
        // that went wrong does not turn the move into a return.
        val f = facts(signature = OURS_SIG, parkFolder = true)
        assertEquals(Step.NEEDS_ATTENTION, status(f).step)
        val g = guide(f, moveActive = true)
        assertEquals(MoveDirection.TO_OURS, g.direction)
        assertEquals(MovePhase.Attention, g.phase)
    }

    @Test
    fun `an official app whose certificate cannot be read needs attention`() {
        val f = facts(signature = null)
        assertEquals(Step.BACK_UP_AND_PARK, status(f).step)
        assertEquals(MovePhase.Attention, guide(f).phase)
    }

    @Test
    fun `an official app newer than our newest build is not possible yet`() {
        assertEquals(MovePhase.NotPossibleYet("2.26.40.1", NEWEST), guide(facts(versionName = "2.26.40.1")).phase)
    }

    @Test
    fun `no backup asks for one`() {
        val g = guide(facts(newestBackupMs = 0, newestBackupCrypt = "none"))
        assertEquals(atStep(GuideStep.BACK_UP), g.phase)
        assertEquals(BackupAge.NONE, g.backupAge)
    }

    @Test
    fun `a backup from before Start asks for a new one until the old one is accepted`() {
        val f = facts(newestBackupMs = STALE_BACKUP)
        assertEquals(BackupAge.STALE, guide(f).backupAge)
        assertEquals(atStep(GuideStep.BACK_UP), guide(f).phase)
        assertEquals(atStep(GuideStep.DOWNLOAD), guide(f, acks = MoveAcks(oldBackup = true)).phase)
    }

    @Test
    fun `an encrypted backup waits for the password to be acknowledged`() {
        val f = facts(newestBackupCrypt = "crypt15")
        assertTrue(guide(f).encrypted)
        assertEquals(atStep(GuideStep.BACK_UP), guide(f).phase)
        assertEquals(atStep(GuideStep.DOWNLOAD), guide(f, acks = MoveAcks(password = true)).phase)
    }

    @Test
    fun `a fresh backup moves on to the download and ticks the backup alone`() {
        val g = guide(facts())
        assertEquals(atStep(GuideStep.DOWNLOAD), g.phase)
        assertEquals(BackupAge.FRESH, g.backupAge)
        assertTrue(g.isDone(GuideStep.BACK_UP))
        assertFalse(g.isDone(GuideStep.DOWNLOAD))
    }

    @Test
    fun `the download can come before the backup`() {
        val g = guide(facts(newestBackupMs = 0, newestBackupCrypt = "none"), downloaded = true)
        assertEquals(atStep(GuideStep.BACK_UP), g.phase)
        assertTrue(g.isDone(GuideStep.DOWNLOAD))
        assertFalse(g.isDone(GuideStep.BACK_UP))
    }

    @Test
    fun `a fresh backup and a downloaded build reach the removal`() {
        val g = guide(facts(), downloaded = true)
        assertEquals(atStep(GuideStep.REMOVE_OLD), g.phase)
        assertTrue(g.officialAtRisk)
        assertFalse(g.inProgress)
    }

    // --- once the backup is parked ---

    @Test
    fun `parked with the official app still installed only the uninstall is left`() {
        assertEquals(Step.UNINSTALL_WHATSAPP, status(parked).step)
        val g = guide(parked)
        assertEquals(atStep(GuideStep.REMOVE_OLD, StepVariant.BACKUP_SAVED), g.phase)
        assertTrue(g.inProgress)
        assertFalse(g.officialAtRisk)
    }

    @Test
    fun `an uninstall that kept the app data says how to undo it`() {
        val f = facts(dataKept = true, waFolder = false, parkFolder = true, manifest = manifest())
        assertEquals(Step.UNINSTALL_WHATSAPP, status(f).step)
        assertEquals(atStep(GuideStep.REMOVE_OLD, StepVariant.DATA_KEPT), guide(f).phase)
    }

    @Test
    fun `parked with an unreadable certificate needs attention`() {
        val f = facts(signature = null, waFolder = false, parkFolder = true, manifest = manifest())
        assertEquals(Step.UNINSTALL_WHATSAPP, status(f).step)
        assertEquals(MovePhase.Attention, guide(f).phase)
    }

    @Test
    fun `parked and removed the build is installed or downloaded again first`() {
        val f = facts(installed = false, waFolder = false, parkFolder = true, manifest = manifest())
        assertEquals(Step.PUT_BACK, status(f).step)
        assertEquals(atStep(GuideStep.INSTALL_NEW), guide(f, downloaded = true).phase)
        assertEquals(atStep(GuideStep.INSTALL_NEW, StepVariant.NEEDS_DOWNLOAD), guide(f).phase)
    }

    @Test
    fun `something in the WhatsApp folder in front of the put-back needs attention`() {
        val f = facts(installed = false, waFolder = true, parkFolder = true, manifest = manifest())
        assertEquals(Step.PUT_BACK, status(f).step)
        assertEquals(MovePhase.Attention, guide(f, downloaded = true).phase)
    }

    @Test
    fun `put back but not yet installed the install is next`() {
        val f = facts(installed = false, waFolder = true, kept = true, manifest = manifest(State.RESTORED))
        assertEquals(Step.INSTALL_PATCHED, status(f).step)
        assertEquals(atStep(GuideStep.INSTALL_NEW), guide(f, downloaded = true).phase)
    }

    @Test
    fun `put back and the official app installed again needs attention`() {
        val f = facts(waFolder = true, kept = true, manifest = manifest(State.RESTORED))
        assertEquals(Step.INSTALL_PATCHED, status(f).step)
        assertEquals(MovePhase.Attention, guide(f, downloaded = true).phase)
    }

    @Test
    fun `ours installed over the put-back backup is restored then finished`() {
        val restored = manifest(State.RESTORED)
        val f = facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L, kept = true,
            manifest = restored)
        assertEquals(Step.VERIFY_AND_RESTORE, status(f).step)
        assertEquals(atStep(GuideStep.RESTORE), guide(f).phase)
        // "My chats are back" lives in the move's record now, so it outlives the process.
        val confirmed = facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L, kept = true,
            manifest = restored, newestBackupMs = NOW - 5 * 60_000L,
            record = MoveRecord.fromManifest(restored, 0L)
                .withRestored(NOW - 4 * 60_000L, MoveRecord.Source.PARKED)
                .withChatsConfirmed(NOW - 60_000L))
        assertEquals(atStep(GuideStep.FINISH, StepVariant.LAST_CHECK), guide(confirmed).phase)
    }

    @Test
    fun `without the spare copy the move still waits for the user's word`() {
        // DONE before 2026-10-10; finishing now needs the user's word and proof.
        val f = facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L,
            manifest = manifest(State.RESTORED))
        assertEquals(Step.VERIFY_AND_RESTORE, status(f).step)
        assertEquals(atStep(GuideStep.RESTORE), guide(f).phase)
    }

    @Test
    fun `every step before the current one is ticked once the backup is parked`() {
        val f = facts(installed = false, waFolder = false, parkFolder = true, manifest = manifest())
        val g = guide(f, downloaded = true)
        listOf(GuideStep.ALLOW_ACCESS, GuideStep.BACK_UP, GuideStep.DOWNLOAD, GuideStep.REMOVE_OLD)
            .forEach { assertTrue(it.name, g.isDone(it)) }
        assertFalse(g.isDone(GuideStep.INSTALL_NEW))
        assertFalse(g.isDone(GuideStep.RESTORE))
    }

    @Test
    fun `with nothing installed and nothing parked there is nothing to do`() {
        val f = facts(installed = false, waFolder = false, newestBackupMs = 0)
        assertEquals(Step.NOTHING_TO_MIGRATE, status(f).step)
        assertEquals(MovePhase.NothingToDo, guide(f).phase)
    }

    @Test
    fun `after the spare copy is removed the guide says all done`() {
        val g = guide(facts(signature = OURS_SIG), acks = MoveAcks(finished = MoveDirection.TO_OURS))
        assertEquals(MoveDirection.TO_OURS, g.finished)
    }

    @Test
    fun `every state of a move into ours reads as that direction`() {
        listOf(
            facts(),
            facts(access = false, waFolder = false, newestBackupMs = 0),
            parked,
            facts(installed = false, waFolder = false, parkFolder = true, manifest = manifest()),
            facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L, kept = true,
                manifest = manifest(State.RESTORED)),
        ).forEach { assertEquals(MoveDirection.TO_OURS, guide(it).direction) }
    }
}
