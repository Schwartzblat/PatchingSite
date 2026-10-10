package site.androidmod.store.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * TokenStore's recovery logic, driven through fakes: the real persistence is
 * EncryptedSharedPreferences over the Android keystore, which does not exist on
 * the JVM - and the failures under test are the keystore's own.
 */
class TokenStoreTest {

    /**
     * Stands in for the encrypted prefs file AND the master key: [reset] deletes
     * both, which is what the real reset does, so a store that failed because its
     * key was lost opens cleanly afterwards.
     */
    private class FakeDisk(
        /** How many upcoming open() calls throw. */
        var failingOpens: Int = 0,
        var failingReads: Int = 0,
        var failingWrites: Int = 0,
    ) {
        var persisted: String? = null
        var opens = 0
        var resets = 0

        fun open(): TokenPersistence {
            opens++
            if (failingOpens > 0) {
                failingOpens--
                throw GeneralSecurityException("keystore unavailable")
            }
            return object : TokenPersistence {
                override fun read(): String? {
                    if (failingReads > 0) {
                        failingReads--
                        // What EncryptedSharedPreferences throws for a value it cannot decrypt.
                        throw SecurityException("Could not decrypt value")
                    }
                    return persisted
                }

                override fun write(value: String?) {
                    if (failingWrites > 0) {
                        failingWrites--
                        throw SecurityException("Could not encrypt value")
                    }
                    persisted = value
                }
            }
        }

        fun reset() {
            resets++
            persisted = null
        }
    }

    private fun store(disk: FakeDisk) = TokenStore(
        open = disk::open,
        reset = disk::reset,
        warn = { _, _ -> },
    )

    @Test
    fun `a healthy store persists the token and opens only once`() {
        val disk = FakeDisk()
        val tokens = store(disk)

        tokens.token = "tok"

        assertEquals("tok", tokens.token)
        assertEquals("tok", disk.persisted)
        assertEquals(1, disk.opens)
        assertEquals(0, disk.resets)
    }

    @Test
    fun `nothing touches the keystore until the token is needed`() {
        val disk = FakeDisk()

        store(disk)

        assertEquals("construction runs on the main thread at app start", 0, disk.opens)
    }

    @Test
    fun `one failed open is retried without wiping anything`() {
        // The keystore throws transient ProviderExceptions around keystore2
        // restarts and just after boot. Resetting on the first one would sign a
        // user out for good over a hiccup.
        val disk = FakeDisk(failingOpens = 1)
        disk.persisted = "tok"

        assertEquals("tok", store(disk).token)
        assertEquals(0, disk.resets)
    }

    @Test
    fun `a store that keeps failing to open is reset and opened again`() {
        // A damaged keystore, or a prefs file whose master key is gone. Before, the
        // store gave up here for good and every sign-in was silently dropped.
        val disk = FakeDisk(failingOpens = 2)
        val tokens = store(disk)

        tokens.token = "tok"

        assertEquals(1, disk.resets)
        assertEquals("tok", disk.persisted)
        assertEquals("tok", tokens.token)
    }

    @Test
    fun `when the store stays broken the token is kept in memory for this process`() {
        // The failure this replaces: the setter no-oped, me() went out with no
        // token and 401'd, and the user could never sign in at all.
        val disk = FakeDisk(failingOpens = Int.MAX_VALUE)
        val tokens = store(disk)

        tokens.token = "tok"

        assertEquals("sign-in must work for this process", "tok", tokens.token)
        assertNull("never written anywhere on disk", disk.persisted)
    }

    @Test
    fun `a store that stays broken is not retried on every access`() {
        // The getter runs on every request. Resetting and reopening the keystore
        // each time would cost a keystore round trip per API call.
        val disk = FakeDisk(failingOpens = Int.MAX_VALUE)
        val tokens = store(disk)

        tokens.token = "tok"
        repeat(5) { tokens.token }

        assertEquals("open, retry, then open once more after the reset", 3, disk.opens)
        assertEquals(1, disk.resets)
    }

    @Test
    fun `signing out of a memory-only session forgets the token`() {
        val disk = FakeDisk(failingOpens = Int.MAX_VALUE)
        val tokens = store(disk)
        tokens.token = "tok"

        tokens.token = null

        assertNull(tokens.token)
    }

    @Test
    fun `an unreadable token is discarded instead of crashing the launch`() {
        // A value that no longer decrypts throws from getString. Unhandled, that
        // crashed the app on every launch until its data was cleared.
        val disk = FakeDisk(failingReads = 1)
        disk.persisted = "garbled"
        val tokens = store(disk)

        assertNull(tokens.token)
        assertEquals(1, disk.resets)
        assertNull("the unreadable value is gone, not retried next launch", disk.persisted)
    }

    @Test
    fun `the store works again after an unreadable token was discarded`() {
        val disk = FakeDisk(failingReads = 1)
        disk.persisted = "garbled"
        val tokens = store(disk)
        tokens.token

        tokens.token = "fresh"

        assertEquals("fresh", disk.persisted)
        assertEquals("fresh", tokens.token)
    }

    @Test
    fun `a failed write is retried once on a freshly reset store`() {
        val disk = FakeDisk(failingWrites = 1)
        val tokens = store(disk)

        tokens.token = "tok"

        assertEquals(1, disk.resets)
        assertEquals("tok", disk.persisted)
    }

    @Test
    fun `a write that keeps failing falls back to memory, never to plaintext`() {
        val disk = FakeDisk(failingWrites = Int.MAX_VALUE)
        disk.persisted = "previous account"
        val tokens = store(disk)

        tokens.token = "tok"

        assertEquals("tok", tokens.token)
        assertNull("the older token must not come back at the next launch", disk.persisted)
    }
}
