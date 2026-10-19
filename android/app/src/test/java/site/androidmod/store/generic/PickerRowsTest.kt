package site.androidmod.store.generic

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import site.androidmod.patch.ApkFacts
import site.androidmod.patch.CantPatch as EngineCantPatch

class PickerRowsTest {

    private fun app(pkg: String = "com.example.app", label: String = "Example", signers: Set<String> = setOf("aa")) =
        InstalledPackage(pkg, label, "1.0", 1L, listOf(File("/data/app/$pkg/base.apk")), signers)

    private fun facts(vararg reasons: EngineCantPatch) =
        Inspection.Done(ApkFacts("com.example.app", 1L, "1.0", 34, setOf("arm64-v8a"), null, reasons.toList()))

    private fun badge(
        app: InstalledPackage = app(),
        inspection: Inspection = facts(),
        obb: Boolean? = false,
        storePackages: Set<String> = emptySet(),
        tested: Set<String> = emptySet(),
        sharedCerts: Set<String> = emptySet(),
        userCert: String? = null,
    ) = pickerBadge(app, inspection, obb, storePackages, tested, sharedCerts, userCert)

    @Test
    fun store_app_is_in_our_store_even_if_unpatchable() {
        val result = badge(inspection = facts(EngineCantPatch.PAIRIP), obb = true, storePackages = setOf("com.example.app"), sharedCerts = setOf("aa"))
        assertEquals(PickerBadge.InOurStore, result)
    }

    @Test
    fun shared_or_user_signer_is_already_patched() {
        assertEquals(PickerBadge.AlreadyPatched, badge(sharedCerts = setOf("aa", "bb")))
        assertEquals(PickerBadge.AlreadyPatched, badge(userCert = "aa"))
        assertEquals(PickerBadge.AlreadyPatched, badge(inspection = Inspection.Pending, sharedCerts = setOf("aa")))
        assertNotEquals(PickerBadge.AlreadyPatched, badge(sharedCerts = setOf("zz"), userCert = "yy"))
    }

    @Test
    fun pending_inspection_is_checking() {
        assertEquals(PickerBadge.Checking, badge(inspection = Inspection.Pending, obb = true))
    }

    @Test
    fun inspect_error_marks_row_unreadable() {
        val inspection = inspectSafely { throw IllegalStateException() }
        assertEquals(Inspection.Unreadable, inspection)
        assertEquals(PickerBadge.CantPatch(listOf("Its files couldn't be read.")), badge(inspection = inspection))
        assertThrows(CancellationException::class.java) { inspectSafely { throw CancellationException("stop") } }
        val done = facts()
        assertEquals(done, inspectSafely { done.facts })
    }

    @Test
    fun reasons_are_listed_in_order() {
        val result = badge(inspection = facts(EngineCantPatch.PAIRIP), obb = true)
        assertEquals(
            PickerBadge.CantPatch(listOf(
                "It uses Google's anti-tamper check (PairIP), so a patched copy won't start.",
                "It keeps extra data in Android/obb, which Android deletes with the app.",
            )),
            result,
        )
        val unreadable = badge(inspection = Inspection.Unreadable, obb = true) as PickerBadge.CantPatch
        assertEquals("Its files couldn't be read.", unreadable.reasons.first())
        assertEquals(2, unreadable.reasons.size)
    }

    @Test
    fun obb_unknown_does_not_block() {
        assertEquals(PickerBadge.NotTested, badge(obb = null))
    }

    @Test
    fun tested_and_not_tested() {
        assertEquals(PickerBadge.Tested, badge(tested = setOf("com.example.app")))
        assertEquals(PickerBadge.NotTested, badge(tested = setOf("other")))
        assertEquals(PickerBadge.CantPatch(listOf("It has already been patched.")),
            badge(inspection = facts(EngineCantPatch.ALREADY_PATCHED), tested = setOf("com.example.app")))
    }

    @Test
    fun only_tested_and_not_tested_are_pickable() {
        val a = app()
        assertTrue(PickerRow(a, PickerBadge.Tested).pickable)
        assertTrue(PickerRow(a, PickerBadge.NotTested).pickable)
        assertFalse(PickerRow(a, PickerBadge.Checking).pickable)
        assertFalse(PickerRow(a, PickerBadge.InOurStore).pickable)
        assertFalse(PickerRow(a, PickerBadge.AlreadyPatched).pickable)
        assertFalse(PickerRow(a, PickerBadge.CantPatch(listOf("x"))).pickable)
    }

    @Test
    fun filter_matches_label_or_package_ignoring_case_and_sorts() {
        val rows = listOf(
            PickerRow(app("com.zeta", "Zeta Player"), PickerBadge.Tested),
            PickerRow(app("com.alpha.video", "Beta"), PickerBadge.Tested),
            PickerRow(app("com.other", "alpha Reader"), PickerBadge.NotTested),
            PickerRow(app("com.none", "Nothing"), PickerBadge.NotTested),
        )
        assertEquals(listOf("alpha Reader", "Beta"), filterRows(rows, "ALPHA").map { it.app.label })
        assertEquals(listOf("alpha Reader", "Beta", "Nothing", "Zeta Player"), filterRows(rows, "").map { it.app.label })
        assertEquals(listOf("Zeta Player"), filterRows(rows, "zeta").map { it.app.label })
    }

    @Test
    fun every_cant_patch_reason_has_text() {
        for (reason in EngineCantPatch.entries) {
            assertTrue(reason.name, cantPatchText(reason).isNotBlank())
        }
        assertEquals("It's in our store.", cantPatchText(EngineCantPatch.IN_OUR_STORE))
    }
}
