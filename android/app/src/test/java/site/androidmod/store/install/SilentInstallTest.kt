package site.androidmod.store.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentInstallTest {

    private val store = "site.androidmod.store"

    @Test
    fun `silent for self`() {
        assertTrue(silentInstallAllowed(34, isSelf = true, installerOfRecord = null, storePackage = store))
    }

    @Test
    fun `silent for an app the store installed`() {
        assertTrue(silentInstallAllowed(34, isSelf = false, installerOfRecord = store, storePackage = store))
    }

    @Test
    fun `dialog for an app installed by someone else`() {
        assertFalse(silentInstallAllowed(34, isSelf = false, installerOfRecord = "com.android.vending", storePackage = store))
    }

    @Test
    fun `dialog for a new install`() {
        assertFalse(silentInstallAllowed(34, isSelf = false, installerOfRecord = null, storePackage = store))
    }

    @Test
    fun `dialog below API 31`() {
        assertFalse(silentInstallAllowed(30, isSelf = true, installerOfRecord = store, storePackage = store))
    }
}
