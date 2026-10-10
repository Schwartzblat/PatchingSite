package site.androidmod.store.move

import com.patcher.migrate.ParkManifest.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the install gate must refuse a store install of ours before anything moves:
 * a move is under way and a WhatsApp that is not ours is installed, so the install
 * can only fail on its certificate, and that failure's "Uninstall and retry" would
 * delete WhatsApp's folder with the backup in it. Found by the 2026-10-09 review.
 */
class StoreInstallGateTest {

    /** Parked from ours, NEWEST, while going back. */
    private val fromOurs = manifest(from = OURS_SIG, versionName = NEWEST, versionCode = 263974L)

    private fun refuses(f: com.patcher.migrate.Facts) = storeInstallWouldStrandBackup(status(f), oursIn(f))

    @Test
    fun `going back with the official app installed and the backup parked refuses`() {
        // The gate would put the backup back in front of an install that cannot happen.
        assertTrue(refuses(facts(versionName = "2.26.40.1", versionCode = 264001L, waFolder = false,
            parkFolder = true, manifest = fromOurs)))
    }

    @Test
    fun `going back with an older official app installed and the backup parked refuses`() {
        assertTrue(refuses(facts(versionName = "2.26.38.80", versionCode = 263880L, waFolder = false,
            parkFolder = true, manifest = fromOurs)))
    }

    @Test
    fun `going back with the backup already in the official app refuses`() {
        assertTrue(refuses(facts(versionName = "2.26.40.1", versionCode = 264001L, kept = true,
            manifest = manifest(State.RESTORED, from = OURS_SIG, versionName = NEWEST, versionCode = 263974L))))
    }

    @Test
    fun `a move in with the official app reinstalled over the put-back backup refuses`() {
        assertTrue(refuses(facts(waFolder = true, kept = true, manifest = manifest(State.RESTORED))))
    }

    @Test
    fun `ours removed with its data kept may be installed again, which is the guide's own fix`() {
        assertFalse(refuses(facts(signature = OURS_SIG, dataKept = true, waFolder = false, parkFolder = true,
            manifest = fromOurs)))
    }

    @Test
    fun `a move in with the official app still installed over its parked backup proceeds`() {
        // Removing it is the guide's next step, and the backup is out of its reach.
        assertFalse(refuses(facts(waFolder = false, parkFolder = true, manifest = manifest())))
    }

    @Test
    fun `a move in with nothing installed proceeds, so the gate can put the backup back`() {
        assertFalse(refuses(facts(installed = false, waFolder = false, parkFolder = true, manifest = manifest())))
    }

    @Test
    fun `ours installed over the put-back backup may be updated`() {
        assertFalse(refuses(facts(signature = OURS_SIG, versionName = NEWEST, versionCode = 263974L, kept = true,
            manifest = manifest(State.RESTORED))))
    }

    @Test
    fun `with no move under way the keep-your-chats dialog decides, not the gate`() {
        assertFalse(refuses(facts()))
    }

    @Test
    fun `without file access the stored hint decides, not this rule`() {
        assertFalse(refuses(facts(access = false, waFolder = false, newestBackupMs = 0)))
    }

    @Test
    fun `a move in whose official app was removed by hand proceeds, so the gate can put the copy back`() {
        assertFalse(refuses(facts(installed = false, waFolder = false, newestBackupMs = 0, record = record())))
    }

    @Test
    fun `going back with only a saved copy and the official app installed refuses`() {
        assertTrue(refuses(facts(versionName = "2.26.40.1", versionCode = 264001L, waFolder = false,
            newestBackupMs = 0, record = record(from = OURS_SIG, versionName = NEWEST, versionCode = 263974L))))
    }
}
