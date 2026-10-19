package site.androidmod.store.install

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import java.io.File
import java.security.MessageDigest

/**
 * Refuses to install anything not signed by the same key as this store app.
 *
 * Both the store and every patched build it serves are signed with the project's
 * key, so a build whose signer differs from ours did not come from us - it is a
 * swapped or MITM'd artifact - and must never reach PackageInstaller or `pm`.
 * This is the client half of an integrity check: the server delivers over HTTPS,
 * but the app makes no assumption the transport (or the configured host) was honest.
 *
 * It runs BEFORE the install session is created, on both the PackageInstaller and
 * the silent root path - the root path especially, since it installs with no system
 * dialog for the user to reject a surprise.
 */
object SignatureVerifier {

    /**
     * Verifies the build in [file] carries exactly this app's signing certificate(s).
     * Returns null to proceed, or an [InstallOutcome.Failure] to abort the install.
     *
     * [contents] comes from [BundleReader]; for a split bundle the identity of the
     * whole install is established by verifying the base APK, because Android will
     * only commit a session whose splits all share the base's signer - an attacker
     * cannot slip a differently-signed split past PackageInstaller once the base is
     * known to be ours.
     */
    fun verify(
        context: Context,
        file: File,
        contents: BuildContents,
        extraTrusted: Set<String> = emptySet(),
    ): InstallOutcome.Failure? {
        val base = contents.parts.firstOrNull()
            ?: return InstallOutcome.Failure(
                "This build contains no installable package.", InstallFailure.UNKNOWN
            )

        // Single APK: the download itself is the archive to read. Bundle: the base
        // split lives inside the zip, and getPackageArchiveInfo needs a real APK
        // path, so stage just the base to an app-private temp file. cacheDir is
        // private to this app (like the download itself), so no other app can read
        // or swap it, and it is deleted as soon as the check is done.
        //
        // The copy was audited for disk use and kept. It does not raise the peak:
        // it is deleted before the install session exists, so the most ever on
        // disk is the download plus the session's staged copy, about 2x the build,
        // with or without it. The ways around it were each worse. Verifying a
        // small config split instead of the base (sound, since Android commits a
        // session only when every split shares the base's signer) fails, because
        // the platform parser refuses a split outright: "Expected base APK, but
        // found split". Reading the signature from the staged session cannot work,
        // since a session exposes streams, never a path. And a zero-copy check
        // means apksig over the zip entry - a new dependency that still has to
        // inflate a DEFLATED entry - or parsing APK Signing Blocks by hand, which
        // is security-critical code to reimplement to save a temporary file.
        val temp = if (base.zipEntry == null) null else stageTemp(context, file, base)
        val archive = temp ?: base.file ?: file
        return try {
            evaluate(
                readSigners(context, context.packageName),
                readArchiveSigners(context, archive),
                extraTrusted,
            )
        } catch (error: Throwable) {
            // Fail closed: any failure to READ a signature is treated as untrusted,
            // never silently passed. A build we cannot verify is not installed.
            InstallOutcome.Failure(
                "This build's signature could not be verified, so it was not installed.",
                InstallFailure.UNTRUSTED_SIGNER,
            )
        } finally {
            temp?.delete()
        }
    }

    /**
     * The pure decision, split out so it can be unit-tested without a device.
     * Each element is the SHA-256 (hex) of a signer's DER-encoded certificate.
     *
     * [extraTrusted] is the user's own per-account signing key, which signs the
     * builds made on the phone. An archive passes when its signers are exactly ours,
     * or when all of them are in [extraTrusted]; a mix of the two (or any unknown
     * signer) is refused, as before.
     */
    fun evaluate(
        ourSigners: Set<String>,
        archiveSigners: Set<String>?,
        extraTrusted: Set<String> = emptySet(),
    ): InstallOutcome.Failure? = when {
        ourSigners.isEmpty() -> InstallOutcome.Failure(
            "This app could not read its own signing certificate, so it cannot verify the " +
                "download. The build was not installed.",
            InstallFailure.UNTRUSTED_SIGNER,
        )
        archiveSigners.isNullOrEmpty() -> InstallOutcome.Failure(
            "This build is not a signed Android package, so it was not installed.",
            InstallFailure.UNTRUSTED_SIGNER,
        )
        archiveSigners != ourSigners && !archiveSigners.isTrustedUserBuild(extraTrusted) ->
            InstallOutcome.Failure(
            InstallErrors.describe(InstallFailure.UNTRUSTED_SIGNER, null),
            InstallFailure.UNTRUSTED_SIGNER,
        )
        else -> null
    }

    private fun Set<String>.isTrustedUserBuild(extraTrusted: Set<String>): Boolean {
        val trusted = extraTrusted.mapTo(HashSet()) { it.lowercase() }
        return all { it.lowercase() in trusted }
    }

    private fun stageTemp(context: Context, file: File, base: ApkPart): File {
        val temp = File.createTempFile("verify-", ".apk", context.cacheDir)
        try {
            BundleReader.openPart(file, base).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
        return temp
    }

    private fun readSigners(context: Context, packageName: String): Set<String> =
        signersOf(
            context.packageManager
                .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
        )

    private fun readArchiveSigners(context: Context, archive: File): Set<String>? {
        val info = context.packageManager.getPackageArchiveInfo(
            archive.path, PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: return null
        return signersOf(info.signingInfo)
    }

    private fun signersOf(signingInfo: SigningInfo?): Set<String> {
        // apkContentsSigners is the CURRENT signer set (for a rotated v3 key, the
        // newest certificate). Comparing current-against-current means our app and
        // the builds we sign rotate together and still match; it deliberately does
        // NOT consult signingCertificateHistory, which would let a retired key pass.
        val signatures = signingInfo?.apkContentsSigners ?: return emptySet()
        return signatures.map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
