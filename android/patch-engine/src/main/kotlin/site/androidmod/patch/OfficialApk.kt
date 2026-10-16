package site.androidmod.patch

import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** Refuses any APK that is not signed by the app's real publisher. */
object OfficialApk {
    // WhatsApp uses v3.1 key rotation: v3.1 signers cover SDK 33+, v3 signers older releases.
    private const val MIN_CHECKED_SDK = 33

    private fun verifyResult(apk: File): ApkVerifier.Result = try {
        ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(MIN_CHECKED_SDK).build().verify()
    } catch (e: Exception) {
        throw PatchException.NotOfficial("Cannot verify the APK signature: ${e.message}")
    }

    private fun certs(r: ApkVerifier.Result): List<X509Certificate> =
        // A malformed signing block can yield null certificates despite the platform types.
        (r.signerCertificates + r.v3SchemeSigners.map { it.certificate } + r.v31SchemeSigners.map { it.certificate })
            .filterNotNull()

    private fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /** SHA-256 (lowercase hex of the DER) of every signing certificate found in [apk]. */
    fun signerCertSha256s(apk: File): Set<String> = certs(verifyResult(apk)).map(::sha256).toSet()

    /** Throws [PatchException.NotOfficial] unless [apk] verifies and a signer is in [allowedCertSha256]. */
    fun verify(apk: File, allowedCertSha256: List<String>) {
        val result = verifyResult(apk)
        val found = certs(result).map(::sha256).toSet()
        if (!result.isVerified) {
            val why = result.errors.joinToString("; ") { it.toString() }.ifEmpty { "no valid signature" }
            throw PatchException.NotOfficial("The APK signature does not verify ($why). Certificates found: ${found.ifEmpty { setOf("none") }.joinToString()}")
        }
        val allowed = allowedCertSha256.map { it.lowercase() }.toSet()
        if (found.none { it in allowed }) {
            throw PatchException.NotOfficial("The APK is not signed by the official publisher. Certificates found: ${found.joinToString()}")
        }
    }
}
