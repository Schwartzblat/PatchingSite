package site.androidmod.store.move

import com.patcher.migrate.ParkManifest.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watcher's rule: when a WhatsApp installed outside the store gets the parked backup back. */
class PutBackOnInstallTest {

    /** Parked from ours, NEWEST, while going back. */
    private val fromOurs = manifest(from = OURS_SIG, versionName = NEWEST, versionCode = 263974L)

    /** The official app installed over a parked backup. */
    private fun official(
        versionCode: Long,
        waFolder: Boolean = false,
        access: Boolean = true,
        dataKept: Boolean = false,
    ) = facts(access = access, versionName = "2.26.40.1", versionCode = versionCode, waFolder = waFolder,
        parkFolder = true, manifest = fromOurs, dataKept = dataKept)

    @Test
    fun `a compatible official app gets the backup back`() {
        assertTrue(shouldPutBackOnInstall(status(official(264001L))))
    }

    @Test
    fun `the same version counts as compatible`() {
        assertTrue(shouldPutBackOnInstall(status(official(263974L))))
    }

    @Test
    fun `an older official app does not, so the backup waits for its update`() {
        assertFalse(shouldPutBackOnInstall(status(official(263880L))))
    }

    @Test
    fun `nothing installed is not an install`() {
        assertFalse(shouldPutBackOnInstall(status(facts(installed = false, waFolder = false, parkFolder = true, manifest = fromOurs))))
    }

    @Test
    fun `the build the backup came from does not`() {
        val f = facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L, waFolder = false,
            parkFolder = true, manifest = fromOurs)
        assertFalse(shouldPutBackOnInstall(status(f)))
    }

    @Test
    fun `an app removed with its data kept does not`() {
        assertFalse(shouldPutBackOnInstall(status(official(264001L, dataKept = true))))
    }

    @Test
    fun `something in the WhatsApp folder does not`() {
        assertFalse(shouldPutBackOnInstall(status(official(264001L, waFolder = true))))
    }

    @Test
    fun `without file access nothing moves`() {
        assertFalse(shouldPutBackOnInstall(status(official(264001L, access = false))))
    }

    @Test
    fun `a backup already put back is left alone`() {
        val f = facts(versionName = "2.26.40.1", versionCode = 264001L, kept = true,
            manifest = manifest(State.RESTORED, from = OURS_SIG, versionName = NEWEST, versionCode = 263974L))
        assertFalse(shouldPutBackOnInstall(status(f)))
    }

    @Test
    fun `ours installed outside the store during a move into ours gets the backup back too`() {
        // The rule never asks which way: the engine's canPutBack already refuses the
        // build the backup came from, whichever that is.
        val f = facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L, waFolder = false,
            parkFolder = true, manifest = manifest())
        assertTrue(shouldPutBackOnInstall(status(f)))
    }

    @Test
    fun `the saved copy goes back into a WhatsApp installed after ours was removed by hand`() {
        val f = facts(versionName = "2.26.40.1", versionCode = 264001L, waFolder = false, newestBackupMs = 0,
            record = record(from = OURS_SIG, versionName = NEWEST, versionCode = 263974L))
        assertTrue(shouldPutBackOnInstall(status(f)))
    }

    @Test
    fun `nor into one older than the saved copy`() {
        val f = facts(versionName = "2.26.38.80", versionCode = 263880L, waFolder = false, newestBackupMs = 0,
            record = record(from = OURS_SIG, versionName = NEWEST, versionCode = 263974L))
        assertFalse(shouldPutBackOnInstall(status(f)))
    }
}
