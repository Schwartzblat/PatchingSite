package site.androidmod.store.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadPathsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun downloads(): File = folder.newFolder("downloads")

    @Test
    fun `a normal build lands in the downloads directory`() {
        val directory = downloads()
        val key = DownloadKey("com.tranzmate", "5.199.1.1804")

        val target = DownloadPaths.targetFile(directory, key, "com.tranzmate-5.199.1.1804.xapk")

        assertEquals("com.tranzmate-5.199.1.1804.xapk", target.name)
        assertEquals(directory.canonicalFile, target.parentFile)
    }

    @Test
    fun `a filename with no suffix falls back to apk`() {
        val target = DownloadPaths.targetFile(downloads(), DownloadKey("com.app", "1.0"), "build")

        assertEquals("com.app-1.0.apk", target.name)
    }

    @Test
    fun `a traversal in the version is refused`() {
        // Versions come from GET /api/list/{package}, and the base URL is
        // user-overridable, so the server supplying them is not necessarily ours.
        val error = runCatching {
            DownloadPaths.targetFile(downloads(), DownloadKey("com.app", "../../shared_prefs/auth"), "x.xml")
        }.exceptionOrNull()

        assertTrue("$error", error is IllegalArgumentException)
    }

    @Test
    fun `a traversal in the package name is refused`() {
        val error = runCatching {
            DownloadPaths.targetFile(downloads(), DownloadKey("../../../databases/users", "1"), "a.db")
        }.exceptionOrNull()

        assertTrue("$error", error is IllegalArgumentException)
    }

    @Test
    fun `a traversal smuggled through the filename suffix is refused`() {
        // The suffix is whatever follows the last dot of the SERVER's filename.
        val error = runCatching {
            DownloadPaths.targetFile(downloads(), DownloadKey("com.app", "1.0"), "build.a/../../escaped")
        }.exceptionOrNull()

        assertTrue("$error", error is IllegalArgumentException)
    }

    @Test
    fun `a nested path is refused even when it stays inside the directory`() {
        // Containment is "directly inside", not "somewhere under": a subdirectory
        // that was never created would fail at write time anyway.
        val error = runCatching {
            DownloadPaths.targetFile(downloads(), DownloadKey("com.app", "1.0/nested"), "x.apk")
        }.exceptionOrNull()

        assertTrue("$error", error is IllegalArgumentException)
    }
}
