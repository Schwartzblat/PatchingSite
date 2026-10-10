package site.androidmod.store.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The install path that works on any device. One system confirmation dialog per
 * install; splits are written into a single session so Android commits them atomically.
 */
class SessionInstaller(private val context: Context) : Installer {

    override val isAvailable: Boolean get() = true

    /** False until the user grants "install unknown apps" for this app. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override suspend fun install(file: File): InstallOutcome = withContext(Dispatchers.IO) {
        val contents = try {
            BundleReader.read(file)
        } catch (error: BundleException) {
            return@withContext InstallOutcome.Failure(
                error.message ?: "This build could not be read.", InstallFailure.UNKNOWN
            )
        }

        // Nothing gets staged unless it is signed by our key. Runs here, before a
        // session exists, so an untrusted build costs nothing and never reaches
        // PackageInstaller.
        SignatureVerifier.verify(context, file, contents)?.let { return@withContext it }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        contents.packageName?.let { params.setAppPackageName(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isSelf(file, contents)) {
            // The store updating itself, the one install that skips the dialog: the
            // user already said yes by tapping Update, and a second "Do you want to
            // update this app?" adds nothing. Android honours this only for an
            // installer holding UPDATE_PACKAGES_WITHOUT_USER_ACTION that is updating
            // itself or is the installer of record, and falls back to the dialog
            // otherwise. The store IS the installer of record for the patched apps it
            // installed, so this check is what keeps their updates on the dialog -
            // widening it would make those silent too.
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
        val result = InstallReceiver.awaiting(sessionId)

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

        InstallReceiver.await(sessionId, result, INSTALL_TIMEOUT_MILLIS) {
            runCatching { installer.abandonSession(sessionId) }
        }
    }

    /**
     * A single APK carries no package name in [BuildContents] - BundleReader only
     * reads one out of a bundle - so ask PackageManager for the archive's.
     */
    private fun isSelf(file: File, contents: BuildContents): Boolean {
        val packageName = contents.packageName
            ?: context.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName
        return packageName == context.packageName
    }

    private companion object {
        /**
         * From commit to verdict. Generous, because it spans the user reading the
         * confirm dialog and Play Protect's "App scan recommended" prompt, and a
         * verdict can trail the dialog by ~20 seconds. Short enough that a dialog
         * Android never showed turns into "try again" within a few minutes, rather
         * than an Install button stuck on "Installing..." until the process dies.
         */
        const val INSTALL_TIMEOUT_MILLIS = 3 * 60 * 1000L
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
