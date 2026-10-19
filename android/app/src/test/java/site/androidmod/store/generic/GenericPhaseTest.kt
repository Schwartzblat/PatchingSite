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
        assertEquals(GenericPhase.Attention("Example was changed outside the store. Put the original back, or contact us."), phase(record(), ourOther))

    @Test fun phase_resumes_at_install_after_original_removed() =
        assertEquals(GenericPhase.InstallPatched, phase(record(), null))

    @Test fun asked_before_is_carried() {
        assertEquals(GenericPhase.RemoveOriginal(true), phase(record(asked = 99L), originalInstalled))
        assertEquals(GenericPhase.RemovePatched(true), phase(record(GenericIntent.RESTORE, asked = 99L), ourBuild))
    }

    @Test fun play_update_mid_flow_needs_attention() {
        val updated = InstalledState(orig, 6L, "play-sha")
        assertTrue(phase(record(), updated) is GenericPhase.Attention)
    }

    // RESTORE
    @Test fun restore_ours_removes_patched() =
        assertEquals(GenericPhase.RemovePatched(false), phase(record(GenericIntent.RESTORE), ourBuild))

    @Test fun restore_not_installed_installs_original() =
        assertEquals(GenericPhase.InstallOriginal, phase(record(GenericIntent.RESTORE), null))

    @Test fun restore_original_installed_is_restored() =
        assertEquals(GenericPhase.Restored, phase(record(GenericIntent.RESTORE), originalInstalled))

    @Test fun restore_other_state_needs_attention() =
        assertTrue(phase(record(GenericIntent.RESTORE), InstalledState(setOf("zz"), 5L, "x")) is GenericPhase.Attention)

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

    @Test fun refresh_original_installed_needs_attention() =
        assertTrue(phase(record(GenericIntent.REFRESH), originalInstalled) is GenericPhase.Attention)

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

    @Test fun refresh_not_installed_needs_attention() =
        assertTrue(phase(record(GenericIntent.REFRESH), null) is GenericPhase.Attention)

    // no intent
    @Test fun none_not_installed_is_removed() =
        assertEquals(GenericPhase.Removed, phase(record(null), null))

    @Test fun none_ours_is_patched() =
        assertEquals(GenericPhase.Patched, phase(record(null), ourBuild))

    @Test fun none_original_is_unpatched() =
        assertEquals(GenericPhase.Unpatched, phase(record(null), originalInstalled))

    @Test fun none_other_needs_attention() =
        assertTrue(phase(record(null), InstalledState(setOf("zz"), 5L, "x")) is GenericPhase.Attention)

    @Test fun null_user_cert_is_never_ours() =
        assertTrue(genericPhase(record(null), ourBuild, null) is GenericPhase.Attention)
}
