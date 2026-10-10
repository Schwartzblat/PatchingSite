package site.androidmod.store.update

import org.junit.Assert.assertEquals
import org.junit.Test
import site.androidmod.store.data.StoreInfo

class UpdateCheckTest {

    private val installed = InstalledBuild(versionCode = 389000, versionName = "1.0")

    private fun published(code: Long?, available: Boolean = true) =
        StoreInfo(available = available, size = 28025397, versionCode = code, versionName = "1.1")

    @Test
    fun `a higher published version code is an update`() {
        assertEquals(
            UpdateCheck.Available(versionCode = 389001, versionName = "1.1", size = 28025397),
            UpdateCheck.of(published(389001), installed),
        )
    }

    @Test
    fun `the same version code is up to date`() {
        assertEquals(UpdateCheck.UpToDate, UpdateCheck.of(published(389000), installed))
    }

    @Test
    fun `an older published build is not offered`() {
        // A locally built release installed over the published one. Android would
        // refuse the downgrade anyway; offering it would be a button that always fails.
        assertEquals(UpdateCheck.UpToDate, UpdateCheck.of(published(388999), installed))
    }

    @Test
    fun `a build the server could not read is unknown, not an update`() {
        assertEquals(UpdateCheck.Unknown, UpdateCheck.of(published(null), installed))
    }

    @Test
    fun `nothing published is unknown`() {
        assertEquals(UpdateCheck.Unknown, UpdateCheck.of(published(null, available = false), installed))
    }

    @Test
    fun `a version label carries the build number, since versionName repeats across releases`() {
        assertEquals("1.0 (build 389123)", versionLabel("1.0", 389123))
    }

    @Test
    fun `a version label without a name is just the build`() {
        assertEquals("Build 389123", versionLabel(null, 389123))
        assertEquals("Build 389123", versionLabel(" ", 389123))
    }
}
