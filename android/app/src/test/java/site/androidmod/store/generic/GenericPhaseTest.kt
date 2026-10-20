package site.androidmod.store.generic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericPhaseTest {
    private val user = "uu"
    private val orig = setOf("aa")
    private val saved = listOf(SavedApk("base.apk", 1, "orig-sha"))
    private val built = listOf(SavedApk("base.apk", 2, "built-sha"))

    private fun record(
        intent: GenericIntent? = GenericIntent.PATCH,
        original: List<SavedApk> = saved,
        built: List<SavedApk> = this.built,
        asked: Long? = null,
    ) = GenericRecord(
        packageName = "com.example.app", label = "Example", versionName = "1.0", versionCode = 5L,
        originalSigners = orig, apkNames = listOf("base.apk"), original = original, built = built,
        intent = intent, uninstallAskedAt = asked,
    )

    private val originalInstalled = InstalledState(orig, 5L, "orig-sha")
    private val ourBuild = InstalledState(setOf(user), 5L, "built-sha")
    private val ourOther = InstalledState(setOf(user), 6L, "newer-sha")

    private fun phase(r: GenericRecord, i: InstalledState?) = genericPhase(r, i, user)

    /** Another signer is installed: only uninstalling it leads anywhere. */
    private val changedOutside =
        GenericPhase.Attention("Example was changed outside the store. Uninstall it, then put the original back from the Library.")

    // PATCH
    @Test fun patch_removed_before_saved() =
        assertEquals(GenericPhase.Attention("Example was removed before its original was saved."), phase(record(original = emptyList(), built = emptyList()), null))

    @Test fun patch_without_original_saves_and_builds() =
        assertEquals(GenericPhase.SaveAndBuild, phase(record(original = emptyList()), originalInstalled))

    @Test fun patch_without_built_saves_and_builds() =
        assertEquals(GenericPhase.SaveAndBuild, phase(record(built = emptyList()), originalInstalled))

    @Test fun patch_with_original_installed_removes_it() =
        assertEquals(GenericPhase.RemoveOriginal(false), phase(record(), originalInstalled))

    @Test fun patch_not_installed_installs_patched() =
        assertEquals(GenericPhase.InstallPatched, phase(record(), null))

    @Test fun patch_our_build_installed_is_patched() =
        assertEquals(GenericPhase.Patched, phase(record(), ourBuild))

    @Test fun patch_other_state_needs_attention() =
        assertEquals(changedOutside, phase(record(), ourOther))

    @Test fun patch_foreign_signer_says_to_uninstall() =
        assertEquals(changedOutside, phase(record(), InstalledState(setOf("zz"), 5L, "x")))

    @Test fun phase_resumes_at_install_after_original_removed() =
        assertEquals(GenericPhase.InstallPatched, phase(record(), null))

    @Test fun asked_before_is_carried() {
        assertEquals(GenericPhase.RemoveOriginal(true), phase(record(asked = 99L), originalInstalled))
        assertEquals(GenericPhase.RemovePatched(true), phase(record(GenericIntent.RESTORE, asked = 99L), ourBuild))
    }

    @Test fun play_update_mid_flow_needs_attention() {
        // The original signer at another version: Stop keeps the app, so the text says Stop, not uninstall.
        val updated = InstalledState(orig, 6L, "play-sha")
        val expected = GenericPhase.Attention("Example was updated outside the store since you picked it. Stop, then pick it again.")
        assertEquals(expected, phase(record(), updated))
        assertEquals(expected, phase(record(asked = 99L), updated))
    }

    // RESTORE
    @Test fun restore_ours_removes_patched() =
        assertEquals(GenericPhase.RemovePatched(false), phase(record(GenericIntent.RESTORE), ourBuild))

    @Test fun restore_not_installed_installs_original() =
        assertEquals(GenericPhase.InstallOriginal, phase(record(GenericIntent.RESTORE), null))

    @Test fun restore_original_installed_is_restored() =
        assertEquals(GenericPhase.Restored, phase(record(GenericIntent.RESTORE), originalInstalled))

    @Test fun restore_other_state_needs_attention() =
        assertEquals(changedOutside, phase(record(GenericIntent.RESTORE), InstalledState(setOf("zz"), 5L, "x")))

    @Test fun restore_without_saved_original_needs_attention() {
        val r = record(GenericIntent.RESTORE, original = emptyList())
        val expected = GenericPhase.Attention("Example's saved original is missing, so it can't be put back.")
        assertEquals(expected, phase(r, ourBuild))
        assertEquals(expected, phase(r, null))
    }

    @Test fun restore_ours_with_different_base_still_removes_patched() =
        assertEquals(GenericPhase.RemovePatched(false), phase(record(GenericIntent.RESTORE), ourOther))

    @Test fun patch_removed_before_saved_wins_even_with_built() =
        assertEquals(GenericPhase.Attention("Example was removed before its original was saved."), phase(record(original = emptyList()), null))

    @Test fun refresh_original_installed_needs_attention() {
        // Stop ends a Refresh and keeps what is installed.
        val expected = GenericPhase.Attention("Example was put back outside the store. Stop to end the Refresh.")
        assertEquals(expected, phase(record(GenericIntent.REFRESH), originalInstalled))
        assertEquals(expected, phase(record(GenericIntent.REFRESH), InstalledState(orig, 6L, "play-sha")))
    }

    @Test fun refresh_foreign_signer_says_to_stop() =
        // Stop ends the Refresh and leaves what is installed: uninstalling first would lead nowhere.
        assertEquals(
            GenericPhase.Attention("Example was changed outside the store. Stop to end the Refresh."),
            phase(record(GenericIntent.REFRESH), InstalledState(setOf("zz"), 5L, "x")),
        )

    // REFRESH
    @Test fun refresh_without_built_saves_and_builds() =
        assertEquals(GenericPhase.SaveAndBuild, phase(record(GenericIntent.REFRESH, built = emptyList()), ourBuild))

    @Test fun refresh_our_build_installed_is_patched() =
        assertEquals(GenericPhase.Patched, phase(record(GenericIntent.REFRESH), ourBuild))

    @Test fun refresh_ours_installed_installs_update() =
        assertEquals(GenericPhase.InstallUpdate, phase(record(GenericIntent.REFRESH), ourOther))

    @Test fun refresh_from_patched_copy_has_no_original_and_still_works() {
        val r = record(GenericIntent.REFRESH, original = emptyList())
        assertEquals(GenericPhase.InstallUpdate, phase(r, ourOther))
        assertEquals(GenericPhase.Patched, phase(r, ourBuild))
    }

    @Test fun refresh_not_installed_says_to_stop() {
        val expected = GenericPhase.Attention("Example was removed outside the store. Stop to end the Refresh.")
        assertEquals(expected, phase(record(GenericIntent.REFRESH), null))
        assertEquals(expected, phase(record(GenericIntent.REFRESH, original = emptyList()), null))
    }

    // no intent
    @Test fun none_not_installed_is_removed() =
        assertEquals(GenericPhase.Removed, phase(record(null), null))

    @Test fun none_ours_is_patched() =
        assertEquals(GenericPhase.Patched, phase(record(null), ourBuild))

    @Test fun none_original_is_unpatched() =
        assertEquals(GenericPhase.Unpatched, phase(record(null), originalInstalled))

    @Test fun none_other_needs_attention() =
        assertEquals(changedOutside, phase(record(null), InstalledState(setOf("zz"), 5L, "x")))

    @Test fun none_other_signer_with_nothing_saved_is_other_signer() {
        // Adopted (no saved original), then the developer's build installed by hand: nothing of ours to protect.
        val adopted = record(null, original = emptyList(), built = emptyList()).copy(originalSigners = emptySet())
        assertEquals(GenericPhase.OtherSigner, phase(adopted, InstalledState(setOf("zz"), 5L, "x")))
        assertEquals(GenericPhase.OtherSigner, phase(record(null, original = emptyList()), InstalledState(setOf("zz"), 5L, "x")))
        // Without the key our own copy reads as another signer: that can't be told, so it stays Attention.
        assertEquals(changedOutside, genericPhase(adopted, ourBuild, null))
        // The original signer, nothing saved: still the original.
        assertEquals(GenericPhase.Unpatched, phase(record(null, original = emptyList()), originalInstalled))
    }

    @Test fun null_user_cert_is_never_ours() =
        assertTrue(genericPhase(record(null), ourBuild, null) is GenericPhase.Attention)
}
