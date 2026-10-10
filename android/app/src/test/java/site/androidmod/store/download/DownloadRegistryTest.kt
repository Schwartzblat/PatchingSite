package site.androidmod.store.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadRegistryTest {

    private val key = DownloadKey("com.tranzmate", "5.199.1.1804")

    @After
    fun tearDown() = DownloadRegistry.reset()

    @Test
    fun `tracks state per package and version`() {
        val other = DownloadKey("com.whatsapp", "2.26.33.76")
        DownloadRegistry.update(key, DownloadState.Queued)
        DownloadRegistry.update(other, DownloadState.Transferring(10, 100))

        assertEquals(DownloadState.Queued, DownloadRegistry.downloads.value[key])
        assertTrue(DownloadRegistry.downloads.value[other] is DownloadState.Transferring)
    }

    @Test
    fun `two versions of one app do not collide`() {
        val older = DownloadKey("com.tranzmate", "5.198.0.1802")
        DownloadRegistry.update(key, DownloadState.Queued)
        DownloadRegistry.update(older, DownloadState.Failed("nope"))

        assertEquals(DownloadState.Queued, DownloadRegistry.downloads.value[key])
        assertEquals(2, DownloadRegistry.downloads.value.size)
    }

    @Test
    fun `a finished download exposes its file`() {
        val file = File.createTempFile("build", ".xapk").also { it.deleteOnExit() }
        DownloadRegistry.update(key, DownloadState.Done(file))

        assertEquals(file, DownloadRegistry.fileFor(key))
    }

    @Test
    fun `fileFor is null while a download is still running`() {
        DownloadRegistry.update(key, DownloadState.Preparing(0.5, "Signing"))
        assertNull(DownloadRegistry.fileFor(key))
    }

    @Test
    fun `concurrent updates from many threads lose nothing`() = runBlocking {
        // Fails against a read-modify-write registry: writers racing on real
        // threads drop each other's entries. This is the scenario DownloadKey
        // exists for - more than one download in flight at once.
        val keys = (1..200).map { DownloadKey("com.app.number$it", "1.0") }

        withContext(Dispatchers.Default) {
            keys.map { key -> async { DownloadRegistry.update(key, DownloadState.Queued) } }.awaitAll()
        }

        assertEquals(200, DownloadRegistry.downloads.value.size)
    }

    @Test
    fun `clear forgets an entry`() {
        DownloadRegistry.update(key, DownloadState.Queued)
        DownloadRegistry.clear(key)
        assertTrue(DownloadRegistry.downloads.value.isEmpty())
    }
}
