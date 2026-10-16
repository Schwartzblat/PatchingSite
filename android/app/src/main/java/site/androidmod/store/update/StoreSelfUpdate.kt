package site.androidmod.store.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import site.androidmod.store.Container
import site.androidmod.store.StoreApp
import site.androidmod.store.data.StoreUpdateRepository
import site.androidmod.store.download.DownloadService
import site.androidmod.store.install.InstallOutcome
import java.io.File

/** The store updating itself: used by the Update button and when a patch needs a newer store. */
object StoreSelfUpdate {

    /**
     * In the downloads directory, which StoreApp prunes at every launch - so the copy left
     * behind by a successful update (the process dies before it can be deleted) is reclaimed
     * by the updated app's first start.
     */
    const val FILENAME = "store-update.apk"

    /**
     * Checks for a newer store and installs it. Null when nothing is available or the install
     * would need "install unknown apps" and it is not granted; otherwise the installer's verdict.
     * @throws site.androidmod.store.data.ApiException, NotEnoughSpaceException, IOException
     */
    suspend fun run(container: Container): InstallOutcome? = run(
        repository = container.storeUpdates,
        installed = container.installedBuild(),
        installer = container.selfInstaller,
        directory = DownloadService.directoryFor(StoreApp.instance),
    )

    internal suspend fun run(
        repository: StoreUpdateRepository,
        installed: InstalledBuild,
        installer: SelfInstaller,
        directory: File,
    ): InstallOutcome? {
        UpdateCheck.of(repository.info(), installed) as? UpdateCheck.Available ?: return null
        if (!installer.canInstall()) return null
        return downloadAndInstall(repository, installer, directory)
    }

    /**
     * Downloads the published build, hands it to [installer] and deletes it, however that ends.
     * A successful self-update normally never returns: Android kills this process to replace
     * its APK. [onInstalling] fires when the download is done.
     */
    suspend fun downloadAndInstall(
        repository: StoreUpdateRepository,
        installer: SelfInstaller,
        directory: File,
        onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> },
        onInstalling: () -> Unit = {},
    ): InstallOutcome {
        val target = File(directory, FILENAME)
        try {
            repository.download(target, onProgress)
            onInstalling()
            return installer.install(target)
        } finally {
            // Before the caller publishes any terminal state, so nothing can still find the file.
            withContext(NonCancellable + Dispatchers.IO) { target.delete() }
        }
    }
}
