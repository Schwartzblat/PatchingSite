package site.androidmod.store.install

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionsTest {

    @Test
    fun `orders the three real version schemes`() {
        assertEquals(VersionOrder.NEWER, Versions.compare("5.199.1.1804", "5.198.0.1802"))
        assertEquals(VersionOrder.NEWER, Versions.compare("2.26.33.76", "2.26.26.73"))
        assertEquals(VersionOrder.NEWER, Versions.compare("42.0", "38.0"))
    }

    @Test
    fun `is numeric, not lexicographic`() {
        // The bug this guards: "5.199" sorts before "5.98" as text.
        assertEquals(VersionOrder.NEWER, Versions.compare("5.199.0.1803", "5.98.0.1803"))
    }

    @Test
    fun `ten is newer than nine`() {
        assertEquals(VersionOrder.NEWER, Versions.compare("2.26.10.1", "2.26.9.1"))
        assertEquals(VersionOrder.OLDER, Versions.compare("2.26.9.1", "2.26.10.1"))
    }

    @Test
    fun `a missing trailing segment counts as zero`() {
        assertEquals(VersionOrder.SAME, Versions.compare("42", "42.0"))
        assertEquals(VersionOrder.SAME, Versions.compare("42.0.0", "42"))
        assertEquals(VersionOrder.NEWER, Versions.compare("42.0.1", "42"))
    }

    @Test
    fun `identical versions are the same`() {
        assertEquals(VersionOrder.SAME, Versions.compare("5.199.1.1804", "5.199.1.1804"))
    }

    @Test
    fun `non-numeric segments fall back to a string comparison`() {
        assertEquals(VersionOrder.NEWER, Versions.compare("1.2-beta", "1.2-alpha"))
        assertEquals(VersionOrder.SAME, Versions.compare("1.2-beta", "1.2-beta"))
    }

    @Test
    fun `blank versions are unknown, never an available update`() {
        assertEquals(VersionOrder.UNKNOWN, Versions.compare("", "42.0"))
        assertEquals(VersionOrder.UNKNOWN, Versions.compare("42.0", ""))
        assertEquals(VersionOrder.UNKNOWN, Versions.compare("  ", "  "))
    }
}
