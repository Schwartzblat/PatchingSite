package site.androidmod.store

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import site.androidmod.store.data.ApiClient
import site.androidmod.store.data.AuthRepository
import site.androidmod.store.data.BugReportRepository
import site.androidmod.store.data.CatalogRepository
import site.androidmod.store.data.DownloadRepository
import site.androidmod.store.data.SettingsStore
import site.androidmod.store.data.StoreUpdateRepository
import site.androidmod.store.data.SubscriptionRepository
import site.androidmod.store.data.SubscriptionStore
import site.androidmod.store.data.TokenStore
import site.androidmod.store.install.Installer
import site.androidmod.store.install.InstalledApps
import site.androidmod.store.install.RootInstaller
import site.androidmod.store.install.SessionInstaller
import site.androidmod.store.install.useRootInstaller
import site.androidmod.store.move.MoveRepository
import site.androidmod.store.patch.BundleCache
import site.androidmod.store.patch.BundleKey
import site.androidmod.store.patch.PatchApi
import site.androidmod.store.patch.SigningKeyStore
import site.androidmod.store.ui.settings.bugReportContext
import site.androidmod.store.update.InstalledBuild
import site.androidmod.store.update.PackageSelfInstaller
import site.androidmod.store.update.SelfInstaller
import java.io.File
import java.io.IOException

/**
 * Manual dependency wiring. This app has few enough collaborators that a DI
 * framework would add more indirection than it removes. Later tasks add their
 * repositories here as `by lazy` properties.
 */
class Container(private val context: Context) {

    val settings = SettingsStore(context)
    val tokens = TokenStore(context)

    // Not lazy: AuthRepository and SubscriptionRepository must share one instance,
    // or signing out would clear a cache the Plan tab is not reading.
    val subscriptions = SubscriptionStore(context)

    val api = ApiClient(
        baseUrl = { settings.baseUrl },
        token = { tokens.token },
        reserveSpace = ::reserveSpace,
        storeVersionCode = { storeVersionCode },
    )

    // Cannot change while the process runs.
    private val storeVersionCode: Long by lazy { installedBuild().versionCode }

    val auth by lazy { AuthRepository(api, tokens, subscriptions, onSignedOut = { signingKeys.clear() }) }
    val catalog by lazy { CatalogRepository(api) { apps ->
        settings.onDevicePackages = apps.filter { it.onDevice }.map { it.packageName }.toSet()
    } }
    val patchApi by lazy { PatchApi(api) }
    val signingKeys by lazy { SigningKeyStore(File(context.filesDir, "signing")) { patchApi.signingKey() } }
    val bundles by lazy { BundleCache(File(context.filesDir, "patch/bundles"), patchApi, BundleKey.publicKey) }
    val downloads by lazy { DownloadRepository(api) }
    val billing by lazy { SubscriptionRepository(api, subscriptions) }
    val sessionInstaller by lazy { SessionInstaller(context) }
    val rootInstaller by lazy { RootInstaller(context) }
    val installedApps by lazy { InstalledApps(context) }
    val storeUpdates by lazy { StoreUpdateRepository(api) }
    val selfInstaller: SelfInstaller by lazy { PackageSelfInstaller(context, this) }
    val bugReports by lazy { BugReportRepository(api) }
    val move by lazy { MoveRepository(context, settings) }

    /** App version, OS and device, as a bug report and a support email carry them. */
    fun deviceContext(): Map<String, String> = bugReportContext(
        installed = installedBuild(),
        release = Build.VERSION.RELEASE,
        sdk = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
    )

    /**
     * This app as the system sees it. PackageManager rather than BuildConfig:
     * release stamps its versionCode per variant output at build time, and the
     * installed package is the only answer that cannot disagree with Android's own
     * downgrade check.
     */
    fun installedBuild(): InstalledBuild {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return InstalledBuild(info.longVersionCode, info.versionName.orEmpty())
    }

    /**
     * Makes room for [bytes] under [directory] before a download writes them, and
     * returns the shortfall (0 when they fit).
     *
     * StorageManager rather than File.usableSpace: usable space leaves out other
     * apps' caches, which the system deletes on request, so on a phone that is
     * merely full of cache it reports "no room" for a download that would fit.
     * getAllocatableBytes counts that cache, and allocateBytes is the request that
     * clears it - without that call the space is only theoretical, and the write
     * would still hit ENOSPC halfway through. Falls back to usable space if the
     * volume cannot be resolved.
     */
    private fun reserveSpace(directory: File, bytes: Long): Long = try {
        val storage = context.getSystemService(StorageManager::class.java)
        val volume = storage.getUuidForPath(directory)
        val allocatable = storage.getAllocatableBytes(volume)
        if (allocatable < bytes) {
            bytes - allocatable
        } else {
            storage.allocateBytes(volume, bytes)
            0L
        }
    } catch (error: IOException) {
        (bytes - directory.usableSpace).coerceAtLeast(0)
    }

    /**
     * Root only in a debug build, only when the user asked for it, AND only when
     * the device actually grants it; see [useRootInstaller]. `isAvailable` shells
     * out to su, so it is asked last and cached by RootInstaller.
     */
    fun installer(): Installer =
        if (useRootInstaller(BuildConfig.DEBUG, settings.silentInstall) { rootInstaller.isAvailable }) {
            rootInstaller
        } else {
            sessionInstaller
        }
}
