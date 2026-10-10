package site.androidmod.store.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadCleanupTest {

    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun tearDown() = DownloadRegistry.reset()

    private fun artifact(directory: File, name: String): File =
        File(directory, name).apply { writeBytes(ByteArray(32)) }

    @Test
    fun `an artifact no registry entry points at is deleted`() {
        // The state after process death: the files are on disk, the registry is
        // empty, and nothing in the app can reach them again.
        val directory = folder.newFolder("downloads")
        val orphans = listOf(artifact(directory, "com.app-1.0.apk"), artifact(directory, "com.app-2.0.xapk"))

        val removed = DownloadCleanup.pruneOrphans(directory)

        assertEquals(2, removed.size)
        assertTrue(orphans.none { it.exists() })
    }

    @Test
    fun `a file a finished download still points at is kept`() {
        val directory = folder.newFolder("downloads")
        val kept = artifact(directory, "com.tranzmate-5.199.1.1804.xapk")
        val orphan = artifact(directory, "com.whatsapp-2.26.36.72.apk")
        DownloadRegistry.update(DownloadKey("com.tranzmate", "5.199.1.1804"), DownloadState.Done(kept))

        val removed = DownloadCleanup.pruneOrphans(directory)

        assertEquals(listOf(orphan), removed)
        assertTrue("a build waiting to be installed is not an orphan", kept.exists())
    }

    @Test
    fun `nothing is pruned while a transfer is still running`() {
        // A partial file has no Done entry, so it looks exactly like an orphan.
        val directory = folder.newFolder("downloads")
        val partial = artifact(directory, "com.app-1.0.apk")
        DownloadRegistry.update(DownloadKey("com.app", "1.0"), DownloadState.Transferring(10, 100))

        assertTrue(DownloadCleanup.pruneOrphans(directory).isEmpty())
        assertTrue(partial.exists())
    }

    @Test
    fun `a failed download's file is still an orphan`() {
        val directory = folder.newFolder("downloads")
        val leftover = artifact(directory, "com.app-1.0.apk")
        DownloadRegistry.update(DownloadKey("com.app", "1.0"), DownloadState.Failed("nope"))

        assertEquals(listOf(leftover), DownloadCleanup.pruneOrphans(directory))
    }

    @Test
    fun `a downloads directory that does not exist yet is not an error`() {
        val missing = File(folder.root, "downloads")

        assertTrue(DownloadCleanup.pruneOrphans(missing).isEmpty())
        assertFalse(missing.exists())
    }

    @Test
    fun `subdirectories are left alone`() {
        val directory = folder.newFolder("downloads")
        val nested = File(directory, "keep-me").apply { mkdirs() }

        assertTrue(DownloadCleanup.pruneOrphans(directory).isEmpty())
        assertTrue(nested.isDirectory)
    }
}
