package site.androidmod.store.patch

import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64

class SigningKeysTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val p12 = byteArrayOf(1, 2, 3, 4, 5)
    private val sha256 = "ab".repeat(32)
    private val sha1 = "cd".repeat(20)
    private var fetches = 0
    private var response = SigningKeyResponse(Base64.getEncoder().encodeToString(p12), "s3cret", "key0", sha256, sha1)

    private fun store(dir: File = File(folder.root, "signing")) = SigningKeyStore(dir) {
        fetches++
        response
    }

    @Test
    fun `missing cache fetches the key again`() = runTest {
        val dir = File(folder.root, "signing")
        val store = store(dir)
        assertNull(store.cached())

        val first = store.get()
        assertEquals(1, fetches)
        assertArrayEquals(p12, File(dir, "signing-key.p12").readBytes())
        assertTrue(File(dir, "signing-key.json").isFile)
        assertEquals("key0", first.alias)
        assertEquals("s3cret", String(first.password))
        assertEquals(sha1, first.certSha1)

        store.get()
        assertEquals(1, fetches)
        assertNotNull(store().cached())

        File(dir, "signing-key.p12").delete()
        store.get()
        assertEquals(2, fetches)
    }

    @Test
    fun `sign out clears the cached key`() = runTest {
        val store = store()
        store.get()
        store.clear()
        assertNull(store.cached())
        assertEquals(0, File(folder.root, "signing").listFiles().orEmpty().size)
    }

    @Test
    fun `bad response is refused and nothing is cached`() = runTest {
        val dir = File(folder.root, "signing")
        val bad = listOf(
            response.copy(pkcs12 = ""),
            response.copy(certSha256 = "abc"),
            response.copy(certSha1 = "zz".repeat(20)),
            response.copy(certSha1 = sha256),
        )
        for (candidate in bad) {
            response = candidate
            try {
                store(dir).get()
                fail("expected IOException for $candidate")
            } catch (expected: IOException) {
                assertFalse(expected.message.orEmpty().contains("s3cret"))
            }
            assertNull(store(dir).cached())
            assertFalse(File(dir, "signing-key.p12").exists())
        }
    }

    @Test
    fun `sign out during a fetch discards the response`() = runTest(UnconfinedTestDispatcher()) {
        val gate = kotlinx.coroutines.CompletableDeferred<SigningKeyResponse>()
        val dir = File(folder.root, "signing")
        val store = SigningKeyStore(dir) { gate.await() }
        val result = async { runCatching { store.get() } }

        store.clear()
        gate.complete(response)

        assertTrue(result.await().exceptionOrNull() is IOException)
        assertNull(store.cached())
        assertEquals(0, dir.listFiles().orEmpty().size)
    }

    @Test
    fun `line-broken base64 is accepted`() = runTest {
        response = response.copy(pkcs12 = Base64.getMimeEncoder(4, "\r\n".toByteArray()).encodeToString(p12))
        assertArrayEquals(p12, store().get().keystore.readBytes())
    }

    @Test
    fun `toString hides the password`() = runTest {
        val key = store().get()
        assertFalse(key.toString().contains("s3cret"))
        assertFalse(response.toString().contains("s3cret"))
    }

    @Test
    fun `formatSha1 groups bytes`() {
        assertEquals("AA:BB:0C", formatSha1("aabb0c"))
        assertEquals(40 + 19, formatSha1(sha1).length)
    }
}
