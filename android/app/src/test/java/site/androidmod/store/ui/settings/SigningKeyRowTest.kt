package site.androidmod.store.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' "Signing key SHA-1" row: only once WhatsApp is patched on the phone, worded for the WhatsApp installed. */
class SigningKeyRowTest {

    private val key = "a".repeat(64)

    @Test
    fun `the row shows only once WhatsApp is patched on this phone`() {
        assertTrue(showsSigningKey(setOf("com.whatsapp")))
        assertTrue(showsSigningKey(setOf("com.tranzmate", "com.whatsapp")))
        // Before the switch every WhatsApp uses the shared key: a per-account SHA-1 would be wrong for Drive.
        assertFalse(showsSigningKey(emptySet()))
        assertFalse(showsSigningKey(setOf("com.tranzmate")))
    }

    @Test
    fun `a WhatsApp signed with the key needs it for Drive`() {
        assertEquals("Needed for Google Drive backup setup.", signingKeyHint(installedSigner = key, keySha256 = key))
        assertEquals(
            "Needed for Google Drive backup setup.",
            signingKeyHint(installedSigner = key.uppercase(), keySha256 = key),
        )
    }

    @Test
    fun `a WhatsApp on another key will use it after the move`() {
        assertEquals(
            "Your WhatsApp will use this key after its one-time move to the new update system.",
            signingKeyHint(installedSigner = "c".repeat(64), keySha256 = key),
        )
    }

    @Test
    fun `with no WhatsApp installed the next one is built with the key`() {
        assertEquals("Needed for Google Drive backup setup.", signingKeyHint(installedSigner = null, keySha256 = key))
    }
}
