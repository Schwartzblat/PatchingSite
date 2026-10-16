package site.androidmod.store.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure decision in [SignatureVerifier.evaluate]. The Android-framework
 * half (reading our own and the archive's certificates through PackageManager) is
 * exercised on-device, matching how InstallErrors/BundleReader are unit-tested here.
 */
class SignatureVerifierTest {

    private val ours = setOf("a1b2c3")

    @Test
    fun `a build signed by our key installs`() {
        assertNull(SignatureVerifier.evaluate(ours, setOf("a1b2c3")))
    }

    @Test
    fun `certificate order does not matter - the signers are a set`() {
        val ourPair = setOf("a1b2c3", "d4e5f6")
        assertNull(SignatureVerifier.evaluate(ourPair, setOf("d4e5f6", "a1b2c3")))
    }

    @Test
    fun `a build signed by a different key is refused as untrusted`() {
        val outcome = SignatureVerifier.evaluate(ours, setOf("deadbeef"))
        assertNotNull(outcome)
        outcome as InstallOutcome.Failure
        assertEquals(InstallFailure.UNTRUSTED_SIGNER, outcome.reason)
        assertTrue(
            "message should say why nothing was installed",
            outcome.message.contains("different key", ignoreCase = true) ||
                outcome.message.contains("tampered", ignoreCase = true),
        )
    }

    @Test
    fun `a signer that is a strict subset of ours is still a mismatch`() {
        val ourPair = setOf("a1b2c3", "d4e5f6")
        val outcome = SignatureVerifier.evaluate(ourPair, setOf("a1b2c3"))
        assertNotNull(outcome)
        assertEquals(InstallFailure.UNTRUSTED_SIGNER, (outcome as InstallOutcome.Failure).reason)
    }

    @Test
    fun `an unsigned or unreadable archive is refused, never installed`() {
        val fromNull = SignatureVerifier.evaluate(ours, null)
        val fromEmpty = SignatureVerifier.evaluate(ours, emptySet())
        assertEquals(InstallFailure.UNTRUSTED_SIGNER, (fromNull as InstallOutcome.Failure).reason)
        assertEquals(InstallFailure.UNTRUSTED_SIGNER, (fromEmpty as InstallOutcome.Failure).reason)
    }

    @Test
    fun `failing to read our own certificate fails closed rather than installing`() {
        // If we cannot establish our own identity we cannot trust anything, so the
        // safe answer is still "do not install" - never null.
        val outcome = SignatureVerifier.evaluate(emptySet(), setOf("a1b2c3"))
        assertNotNull(outcome)
        assertTrue((outcome as InstallOutcome.Failure).message.contains("verify", ignoreCase = true))
    }

    @Test
    fun `user key build is trusted`() {
        assertNull(SignatureVerifier.evaluate(ours, setOf("feed01"), extraTrusted = setOf("FEED01")))
    }

    @Test
    fun `unknown signer is still refused`() {
        val outcome = SignatureVerifier.evaluate(ours, setOf("deadbeef"), extraTrusted = setOf("feed01"))
        assertEquals(InstallFailure.UNTRUSTED_SIGNER, (outcome as InstallOutcome.Failure).reason)
    }

    @Test
    fun `mixed signers are refused`() {
        val mixed = SignatureVerifier.evaluate(ours, setOf("a1b2c3", "feed01"), extraTrusted = setOf("feed01"))
        val withUnknown = SignatureVerifier.evaluate(ours, setOf("feed01", "deadbeef"), extraTrusted = setOf("feed01"))
        assertEquals(InstallFailure.UNTRUSTED_SIGNER, (mixed as InstallOutcome.Failure).reason)
        assertEquals(InstallFailure.UNTRUSTED_SIGNER, (withUnknown as InstallOutcome.Failure).reason)
    }
}
