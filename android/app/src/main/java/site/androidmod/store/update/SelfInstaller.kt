package site.androidmod.store.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import site.androidmod.store.Container
import site.androidmod.store.install.InstallFailure
import site.androidmod.store.install.InstallOutcome
import java.io.File

/**
 * What the updater needs from the install stack, narrowed to an interface so
 * UpdateViewModel can be tested on the JVM without PackageManager.
 */
interface SelfInstaller {
    /** False when the install would need "install unknown apps" and it is not granted. */
    suspend fun canInstall(): Boolean

    suspend fun install(file: File): InstallOutcome
}

/**
 * Installs a store update through [Container.installer] - root when the user
 * chose it, PackageInstaller otherwise - so it passes the same SignatureVerifier
 * gate as every patched app.
 */
class PackageSelfInstaller(
    private val context: Context,
    private val container: Container,
) : SelfInstaller {

    // Both off the main thread: installer() reads rootInstaller.isAvailable, which
    // shells out to `su`, and a first root grant can block for seconds.
    override suspend fun canInstall(): Boolean = withContext(Dispatchers.IO) {
        // Gated on the installer that will actually run, as LibraryViewModel does:
        // silent install with no working root falls back to the session installer,
        // which does consult the "install unknown apps" appop.
        val installer = container.installer()
        installer !== container.sessionInstaller || container.sessionInstaller.canInstall()
    }

    override suspend fun install(file: File): InstallOutcome = withContext(Dispatchers.IO) {
        // SignatureVerifier proves the build is ours, not that it is THIS app: every
        // patched app carries the same key, so a WhatsApp build copied over
        // store.apk by mistake would pass it and install as a second app while the
        // store "updated" to nothing.
        val published = context.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName
        if (published != context.packageName) {
            return@withContext InstallOutcome.Failure(
                "The published update is not a build of this app " +
                    "(${published ?: "unreadable package"}), so it was not installed.",
                InstallFailure.UNKNOWN,
            )
        }

        container.settings.pendingSelfUpdate = true
        val outcome = container.installer().install(file)
        // Success usually never gets here - the install kills this process - and
        // the updated build's UpdatedReceiver consumes the flag instead.
        if (outcome is InstallOutcome.Failure) container.settings.pendingSelfUpdate = false
        outcome
    }
}
