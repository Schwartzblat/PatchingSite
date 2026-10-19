package site.androidmod.store

import android.content.Context
import android.content.pm.PackageManager
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
import site.androidmod.store.generic.GenericApi
import site.androidmod.store.generic.GenericRepository
import site.androidmod.store.install.Installer
import site.androidmod.store.install.InstalledApps
import site.androidmod.store.install.RootInstaller
import site.androidmod.store.install.SessionInstaller
import site.androidmod.store.install.useRootInstaller
import site.androidmod.store.move.MoveRepository
import site.androidmod.store.move.WHATSAPP_PACKAGE
import site.androidmod.patch.ApkSigner
import site.androidmod.patch.DexHash
import site.androidmod.patch.FastSigner
import site.androidmod.patch.OfficialApk
import site.androidmod.patch.PatchBundle
import site.androidmod.patch.PatchEngine
import site.androidmod.store.patch.ArchiveInfo
import site.androidmod.store.patch.BundleCache
import site.androidmod.store.patch.BundleKey
import site.androidmod.store.patch.LatestPatch
import site.androidmod.store.patch.OfficialDownloader
import site.androidmod.store.patch.PatchApi
import site.androidmod.store.patch.PatchJob
import site.androidmod.store.patch.PatchServer
import site.androidmod.store.patch.SigningKey
import site.androidmod.store.patch.SigningKeyStore
import site.androidmod.store.ui.settings.bugReportContext
import site.androidmod.store.update.InstalledBuild
import site.androidmod.store.update.PackageSelfInstaller
import site.androidmod.store.update.SelfInstaller
import java.io.File
import java.io.IOException
import java.security.MessageDigest

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

    /** Display names from the last catalogue that loaded, for notifications; never needs the network. */
    @Volatile
    var appNames: Map<String, String> = emptyMap()
        private set

    val catalog by lazy { CatalogRepository(api) { apps ->
        appNames = apps.filter { it.name.isNotBlank() }.associate { it.packageName to it.name }
        settings.onDevicePackages = apps.filter { it.onDevice }.map { it.packageName }.toSet()
    } }
    val patchApi by lazy { PatchApi(api) }
    val genericApi by lazy { GenericApi(api) }
    val generic by lazy { GenericRepository(genericApi) }
    val signingKeys by lazy { SigningKeyStore(File(context.filesDir, "signing")) { patchApi.signingKey() } }
    val bundles by lazy { BundleCache(File(context.filesDir, "patch/bundles"), patchApi, BundleKey.publicKey) }
    val downloads by lazy { DownloadRepository(api) }
    val billing by lazy { SubscriptionRepository(api, subscriptions) }
    private val trustedKeys: () -> Set<String> = { setOfNotNull(signingKeys.cached()?.certSha256) }
    val sessionInstaller by lazy { SessionInstaller(context, trustedKeys) }
    val rootInstaller by lazy { RootInstaller(context, trustedKeys) }
    val installedApps by lazy { InstalledApps(context) }
    val storeUpdates by lazy { StoreUpdateRepository(api) }
    val selfInstaller: SelfInstaller by lazy { PackageSelfInstaller(context, this) }
    val bugReports by lazy { BugReportRepository(api) }
    val move by lazy {
        MoveRepository(
            context,
            settings,
            userCertSha256 = { signingKeys.cached()?.certSha256 },
            onDevice = { WHATSAPP_PACKAGE in settings.onDevicePackages },
        )
    }

    /**
     * One on-device build of [pkg]. Each package has its own work directory, so jobs for
     * different packages never clear each other's files; StoreApp wipes `patch/work` at launch.
     */
    fun patchJob(pkg: String): PatchJob = PatchJob(
        server = object : PatchServer {
            override suspend fun latest(pkg: String): LatestPatch = patchApi.latest(pkg)
            override suspend fun record(pkg: String, patcherVersion: String, apkSha256: String?, dexSha256: String?) =
                patchApi.record(pkg, patcherVersion, apkSha256, dexSha256)
            override suspend fun bundle(pkg: String, patcherVersion: String): PatchBundle = bundles.open(pkg, patcherVersion)
            override suspend fun appToken(pkg: String): String = patchApi.appToken(pkg)
            override suspend fun signingKey(): SigningKey = signingKeys.get()
        },
        downloader = object : OfficialDownloader {
            override suspend fun size(url: String): Long? = api.size(url)
            override suspend fun download(
                url: String,
                target: File,
                digest: MessageDigest?,
                onProgress: (bytesRead: Long, total: Long) -> Unit,
            ) = api.download(url, target, digest = digest, onProgress = onProgress)
        },
        reserve = ::reserveSpace,
        verifyOfficial = OfficialApk::verify,
        dexHash = DexHash::dexSha256,
        patcher = { request, signer -> PatchEngine(signer).patch(request) },
        signerFor = { key -> fastSigner(key) },
        inspector = ::inspectArchive,
        signedIn = { tokens.token != null },
        sdkInt = Build.VERSION.SDK_INT,
        storeVersionCode = storeVersionCode,
        workDir = File(context.filesDir, "patch/work/$pkg"),
    )

    // The binary ships as libfastsigner.so and is extracted (useLegacyPackaging) so it can be executed.
    private fun fastSigner(key: SigningKey): ApkSigner =
        FastSigner(File(context.applicationInfo.nativeLibraryDir, "libfastsigner.so"), key.keystore, key.password, key.alias)

    /** What PackageManager makes of [file]: its package, version and the SHA-256 of each signer certificate. */
    @Suppress("DEPRECATION")
    private fun inspectArchive(file: File): ArchiveInfo? {
        val info = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return null
        val signers = info.signingInfo?.apkContentsSigners.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        return ArchiveInfo(info.packageName, info.longVersionCode, signers.toSet())
    }

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
