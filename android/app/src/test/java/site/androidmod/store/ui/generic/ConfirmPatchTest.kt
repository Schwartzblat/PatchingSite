package site.androidmod.store.ui.generic

import androidx.compose.runtime.saveable.SaverScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import site.androidmod.patch.Capability
import site.androidmod.store.generic.GenericFlow
import site.androidmod.store.generic.GenericRecords
import site.androidmod.store.generic.InstalledPackage
import site.androidmod.store.install.InstallOutcome

class ConfirmPatchTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val app = InstalledPackage("com.example.app", "Example", "1.0", 1L, listOf(File("/data/app/base.apk")), setOf("aa"))

    @Test
    fun defaultsAreAdsOnIapOff() {
        val fresh = PatchChoice()
        assertTrue(fresh.ads)
        assertFalse(fresh.iap)
        assertEquals(setOf(Capability.ADS), fresh.capabilities())
        // Each switch is the customer's to flip; the screen renders whatever the pair says.
        assertEquals(setOf(Capability.IAP), fresh.copy(ads = false, iap = true).capabilities())
        assertEquals(setOf(Capability.ADS, Capability.IAP), fresh.copy(iap = true).capabilities())
    }

    /** The screen keeps the switches through a rotation with this saver, so every pair must come back as it went in. */
    @Test
    fun choiceSurvivesSaveAndRestore() {
        val scope = SaverScope { true }
        for (ads in listOf(true, false)) for (iap in listOf(true, false)) {
            val choice = PatchChoice(ads, iap)
            val saved = with(PatchChoice.Saver) { scope.save(choice) }!!
            assertEquals(choice, PatchChoice.Saver.restore(saved))
        }
    }

    @Test
    fun untogglingBothDisablesThePatchButton() {
        assertTrue(PatchChoice().canPatch)
        assertTrue(PatchChoice(ads = false, iap = true).canPatch)
        assertFalse(PatchChoice().copy(ads = false).canPatch)
        assertFalse(PatchChoice(ads = false, iap = false).canPatch)
    }

    @Test
    fun bulletsMatchTheMix() {
        val adsOnly = "Your phone removes its ads. Nothing is uploaded."
        val iapOnly = "Example's purchases will succeed without paying. Nothing is uploaded."
        val both = "Your phone removes its ads and makes its purchases succeed without paying. Nothing is uploaded."
        assertEquals(adsOnly, PatchChoice().firstBullet("Example"))
        assertEquals(iapOnly, PatchChoice(ads = false, iap = true).firstBullet("Example"))
        assertEquals(both, PatchChoice(ads = true, iap = true).firstBullet("Example"))
    }

    @Test
    fun the_confirmed_mix_is_written_on_the_record() {
        val records = GenericRecords(File(folder.newFolder(), "generic"))
        val flow = GenericFlow(
            records = records,
            installedState = { null },
            userCert = { null },
            install = { _, _ -> InstallOutcome.Success },
            sha256 = { "" },
            now = { 0L },
            isBusy = { false },
            obb = { false },
        )
        val record = flow.begin(app, setOf(Capability.IAP))
        assertEquals(listOf("iap"), record.capabilities)
        assertEquals(listOf("iap"), records.get(app.packageName)?.capabilities)
    }

    @Test
    fun a_started_patch_opens_the_run_screen() = runTest {
        var asked = false
        assertNull(confirmMessage(app) { asked = true; true })
        assertTrue(asked)
        // Not started by Android: the record is there, and the run screen offers Continue.
        assertNull(confirmMessage(app) { false })
    }

    @Test
    fun a_refusal_shows_its_own_words() = runTest {
        assertEquals(
            "Example is already being patched or put back.",
            confirmMessage(app) { throw IllegalStateException("Example is already being patched or put back.") },
        )
    }

    @Test
    fun a_failed_write_shows_a_message_instead_of_crashing() = runTest {
        assertEquals("Patching couldn't start. Try again.", confirmMessage(app) { throw IOException("No space left on device") })
        assertEquals("Patching couldn't start. Try again.", confirmMessage(app) { throw IllegalArgumentException("bad") })
    }

    @Test
    fun cancellation_still_propagates() {
        // CancellationException is an IllegalStateException on the JVM: it must not read as a refusal.
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { confirmMessage(app) { throw CancellationException("left the screen") } }
        }
    }
}
