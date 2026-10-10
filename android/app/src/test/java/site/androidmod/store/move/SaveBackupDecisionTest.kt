package site.androidmod.store.move

import com.patcher.migrate.Facts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the store asks the engine to save the backup. It runs on every open of the
 * store, so it must say yes to exactly the backup step 2 accepts, and no for
 * everything else - in particular a Start forgotten for days, which would otherwise
 * claim tonight's automatic backup as a move.
 */
class SaveBackupDecisionTest {

    private fun decide(f: Facts, startedAt: Long = STARTED_AT, oldChosen: Boolean = false) =
        shouldSaveBackup(status(f), startedAt, NOW, oldChosen)

    @Test
    fun `a fresh backup after Start is saved`() {
        assertTrue(decide(facts()))
    }

    @Test
    fun `ours is saved too, on the way back`() {
        assertTrue(decide(facts(signature = OURS_SIG)))
    }

    @Test
    fun `a backup from before Start is saved only once the user chooses it`() {
        val f = facts(newestBackupMs = STALE_BACKUP)
        assertFalse(decide(f))
        assertTrue(decide(f, oldChosen = true))
    }

    @Test
    fun `no Start, or a Start over a day old, saves nothing`() {
        assertFalse(decide(facts(), startedAt = 0L))
        val longAgo = NOW - MOVE_START_WINDOW_MILLIS - 1
        assertFalse(decide(facts(newestBackupMs = NOW - 60_000L), startedAt = longAgo))
    }

    @Test
    fun `a move with a saved copy keeps saving newer backups after a day`() {
        val longAgo = NOW - MOVE_START_WINDOW_MILLIS - 60 * 60_000L
        assertTrue(decide(facts(newestBackupMs = NOW - 30_000L, record = record()), startedAt = longAgo))
    }

    @Test
    fun `no backup saves nothing`() {
        assertFalse(decide(facts(newestBackupMs = 0, newestBackupCrypt = "none")))
    }

    @Test
    fun `without access, or with no WhatsApp, or one kept only as data, nothing is saved`() {
        assertFalse(decide(facts(access = false)))
        assertFalse(decide(facts(installed = false, waFolder = false, newestBackupMs = 0)))
        assertFalse(decide(facts(dataKept = true)))
    }

    @Test
    fun `an unreadable certificate saves nothing`() {
        assertFalse(decide(facts(signature = null)))
    }

    @Test
    fun `once parked there is nothing left to save`() {
        assertFalse(decide(facts(waFolder = false, parkFolder = true, manifest = manifest())))
    }

    @Test
    fun `the backup already saved is not saved again`() {
        assertFalse(decide(facts(record = record())))
    }

    @Test
    fun `a newer backup than the saved one is offered to the engine`() {
        assertTrue(decide(facts(newestBackupMs = NOW - 30_000L, record = record())))
    }

    @Test
    fun `a saved copy that went missing is made again`() {
        assertTrue(decide(facts(record = record(), saved = false)))
    }
}
