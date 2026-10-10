package site.androidmod.store.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerChoiceTest {

    @Test
    fun `a release build never installs with root`() {
        // The root path has never run on a device. Whatever a preference left over
        // from a debug build says, release goes through PackageInstaller.
        var probed = 0

        val root = useRootInstaller(debugBuild = false, silentInstall = true) { probed++; true }

        assertFalse(root)
        assertEquals("not even asked: probing runs su, which pops a superuser prompt", 0, probed)
    }

    @Test
    fun `root is not probed unless silent install is on`() {
        var probed = 0

        val root = useRootInstaller(debugBuild = true, silentInstall = false) { probed++; true }

        assertFalse(root)
        assertEquals(0, probed)
    }

    @Test
    fun `a debug build with silent install on uses root only when it is granted`() {
        assertTrue(useRootInstaller(debugBuild = true, silentInstall = true) { true })
        assertFalse(useRootInstaller(debugBuild = true, silentInstall = true) { false })
    }
}
