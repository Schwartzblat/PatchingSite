package site.androidmod.store.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The install path that works on any device. One system confirmation dialog per
 * install; splits are written into a single session so Android commits them atomically.
 */
class SessionInstaller(
    private val context: Context,
    private val trustedSigners: () -> Set<String> = { emptySet() },
) : Installer {

    override val isAvailable: Boolean get() = true

    /** False until the user grants "install unknown apps" for this app. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override suspend fun install(file: File, confirmation: InstallConfirmation): InstallOutcome = withContext(Dispatchers.IO) {
        val contents = try {
            BundleReader.read(file)
        } catch (error: BundleException) {
            return@withContext InstallOutcome.Failure(
                error.message ?: "This build could not be read.", InstallFailure.UNKNOWN
            )
        }
        installContents(file, contents, confirmation, emptySet())
    }

    override suspend fun installApks(
        apks: List<File>,
        confirmation: InstallConfirmation,
        extraTrusted: Set<String>,
    ): InstallOutcome = withContext(Dispatchers.IO) {
        val contents = try {
            apkSetContents(apks, basePackageName(apks))
        } catch (error: BundleException) {
            return@withContext InstallOutcome.Failure(
                error.message ?: "These files could not be installed.", InstallFailure.UNKNOWN
            )
        }
        installContents(apks.first(), contents, confirmation, extraTrusted)
    }

    private suspend fun installContents(
        file: File,
        contents: BuildContents,
        confirmation: InstallConfirmation,
        extraTrusted: Set<String>,
    ): InstallOutcome = withContext(Dispatchers.IO) {
        // Nothing gets staged unless it is signed by our key (or, for a restore, by a
        // signer the caller named in extraTrusted). Runs here, before a session exists,
        // so an untrusted build costs nothing and never reaches PackageInstaller.
        SignatureVerifier.verify(context, file, contents, trustedSigners() + extraTrusted)
            ?.let { return@withContext it }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        contents.packageName?.let { params.setAppPackageName(it) }
        val packageName = installTarget(file, contents)
        if (silentInstallAllowed(
                Build.VERSION.SDK_INT,
                packageName == context.packageName,
                packageName?.let { installerOfRecord(it) },
                context.packageName,
            )
        ) {
            // No dialog for the store updating itself, or an app this store installed
            // (the installer of record). The user already said yes by tapping Update.
            // Android honours this only for an installer holding
            // UPDATE_PACKAGES_WITHOUT_USER_ACTION in those cases, and falls back to
            // the dialog (STATUS_PENDING_USER_ACTION, handled by InstallReceiver)
            // otherwise.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        // createSession throws IOException when the device cannot stage the
        // install - out of space being the obvious case. install() is contracted to
        // RESOLVE to an outcome, never to throw, so this cannot sit outside a try:
        // the caller in Task 16 would crash instead of showing the failure.
        val sessionId = try {
            installer.createSession(params)
        } catch (error: Throwable) {
            return@withContext InstallErrors.failure(error.message)
        }
        val result = InstallReceiver.awaiting(sessionId, confirmation)

        try {
            installer.openSession(sessionId).use { session ->
                contents.parts.forEach { part ->
                    // -1 when the zip did not record a size; PackageInstaller accepts it.
                    val length = if (part.size >= 0) part.size else -1L
                    session.openWrite(part.sessionName, 0, length).use { sink ->
                        BundleReader.openPart(file, part).use { source -> source.copyTo(sink) }
                        session.fsync(sink)
                    }
                }
                session.commit(statusIntent(sessionId).intentSender)
            }
        } catch (error: CancellationException) {
            // Never swallowed: cancelling an install must propagate, not be reported
            // as a failed one. Cleanup still runs.
            InstallReceiver.forget(sessionId)
            runCatching { installer.abandonSession(sessionId) }
            throw error
        } catch (error: Throwable) {
            InstallReceiver.forget(sessionId)
            runCatching { installer.abandonSession(sessionId) }
            return@withContext InstallOutcome.Failure(
                "The build could not be staged for install: ${error.message}", InstallFailure.UNKNOWN
            )
        }

        InstallReceiver.await(sessionId, result, confirmation.timeoutMillis) {
            runCatching { installer.abandonSession(sessionId) }
        }
    }

    /** The base's package name; the session is pinned to it. Throws when it cannot be read. */
    private fun basePackageName(apks: List<File>): String =
        apks.firstOrNull()?.let { context.packageManager.getPackageArchiveInfo(it.path, 0)?.packageName }
            ?: throw BundleException("The base APK could not be read.")

    /**
     * A single APK carries no package name in [BuildContents] - BundleReader only
     * reads one out of a bundle - so ask PackageManager for the archive's.
     */
    private fun installTarget(file: File, contents: BuildContents): String? =
        contents.packageName ?: context.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName

    /** Who installed [packageName], or null when it is not installed (or unknown). */
    private fun installerOfRecord(packageName: String): String? = try {
        context.packageManager.getInstallSourceInfo(packageName).installingPackageName
    } catch (error: PackageManager.NameNotFoundException) {
        null
    }

    private fun statusIntent(sessionId: Int): PendingIntent {
        val intent = Intent(InstallReceiver.ACTION).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}

/**
 * Whether the install may skip the confirm dialog: API 31+ and either the store
 * updating itself or an app whose installer of record is the store.
 */
internal fun silentInstallAllowed(
    sdkInt: Int,
    isSelf: Boolean,
    installerOfRecord: String?,
    storePackage: String,
): Boolean = sdkInt >= 31 && (isSelf || installerOfRecord == storePackage)
