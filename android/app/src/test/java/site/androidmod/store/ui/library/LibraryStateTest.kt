package site.androidmod.store.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import site.androidmod.store.install.InstalledApp
import site.androidmod.store.install.VersionOrder

/**
 * The rule that decides whether a raised signature conflict survives a refresh.
 *
 * It matters because refresh() now runs on every ON_RESUME of the Library screen,
 * and the resume that follows dismissing the system install dialog is exactly when
 * the conflict has just been raised. A refresh that cleared it would delete the
 * "Uninstall and retry" button before the user could reach it.
 */
class LibraryStateTest {

    private fun entry(packageName: String, installedVersion: String?) = LibraryEntry(
        packageName = packageName,
        name = packageName,
        installed = installedVersion?.let { InstalledApp(packageName, it) },
        latest = "42.0",
        order = VersionOrder.UNKNOWN,
    )

    @Test
    fun `a conflict survives a refresh while the offending copy is still installed`() {
        val entries = listOf(entry("com.whatsapp", "2.26.36.72"), entry("com.tranzmate", null))

        assertEquals("com.whatsapp", conflictAfterRefresh("com.whatsapp", entries))
    }

    @Test
    fun `a conflict is dropped once the package is gone`() {
        // The uninstall landed, so the advice is stale and installing is viable again.
        val entries = listOf(entry("com.whatsapp", null))

        assertNull(conflictAfterRefresh("com.whatsapp", entries))
    }

    @Test
    fun `a package the catalogue does not list keeps its conflict`() {
        // "Not listed" is not the observation "not installed", and losing the
        // recovery affordance is the failure worth avoiding.
        assertEquals("com.whatsapp", conflictAfterRefresh("com.whatsapp", listOf(entry("com.tranzmate", null))))
    }

    @Test
    fun `no conflict stays no conflict`() {
        assertNull(conflictAfterRefresh(null, listOf(entry("com.whatsapp", "2.26.36.72"))))
    }

    @Test
    fun `an empty catalogue does not silently drop a conflict`() {
        // A failed or still-loading catalogue read yields no entries; that is not
        // evidence the offending copy was removed.
        assertEquals("com.whatsapp", conflictAfterRefresh("com.whatsapp", emptyList()))
    }
}
