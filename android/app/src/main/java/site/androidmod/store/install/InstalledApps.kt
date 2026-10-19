package site.androidmod.store.install

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import java.security.MessageDigest
import site.androidmod.store.generic.InstalledPackage

data class InstalledApp(
    val packageName: String,
    val versionName: String,
    val versionCode: Long = 0,
    val sourceDir: String? = null,
    /** Lowercase SHA-256 of the first signing certificate, hashed as the engine hashes WhatsApp's. */
    val signerSha256: String? = null,
)

/**
 * What is on the device. Needs QUERY_ALL_PACKAGES because the catalogue is
 * server-driven and the package set is not known at build time.
 */
class InstalledApps(private val context: Context) {

    fun find(packageName: String): InstalledApp? = try {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        InstalledApp(
            packageName,
            info.versionName.orEmpty(),
            info.longVersionCode,
            info.applicationInfo?.sourceDir,
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.let { sha256Hex(it.toByteArray()) },
        )
    } catch (error: PackageManager.NameNotFoundException) {
        null
    }

    /** Apps the customer installed and can open: no system apps, no store itself. */
    fun listUserApps(): List<InstalledPackage> {
        val pm = context.packageManager
        return installedPackageInfos().mapNotNull { info ->
            val app = info.applicationInfo ?: return@mapNotNull null
            val system = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            if (app.flags and system != 0) return@mapNotNull null
            if (info.packageName == context.packageName) return@mapNotNull null
            if (pm.getLaunchIntentForPackage(info.packageName) == null) return@mapNotNull null
            toInstalledPackage(info, app)
        }
    }

    fun packageInfo(packageName: String): InstalledPackage? = try {
        val info = packageInfoWithSigners(packageName)
        info.applicationInfo?.let { toInstalledPackage(info, it) }
    } catch (error: PackageManager.NameNotFoundException) {
        null
    }

    fun signersOf(packageName: String): Set<String> = try {
        signersOf(packageInfoWithSigners(packageName))
    } catch (error: PackageManager.NameNotFoundException) {
        emptySet()
    }

    /** Whether the app keeps files in Android/obb; null when that can't be read. */
    fun hasObbFiles(packageName: String): Boolean? = try {
        File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName").listFiles()?.isNotEmpty()
    } catch (error: Exception) {
        null
    }

    private fun toInstalledPackage(info: PackageInfo, app: ApplicationInfo) = InstalledPackage(
        packageName = info.packageName,
        label = app.loadLabel(context.packageManager).toString(),
        versionName = info.versionName.orEmpty(),
        versionCode = info.longVersionCode,
        apks = listOf(File(app.sourceDir)) + app.splitSourceDirs.orEmpty().map(::File),
        signerSha256s = signersOf(info),
    )

    private fun signersOf(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()

    @Suppress("DEPRECATION")
    private fun packageInfoWithSigners(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    @Suppress("DEPRECATION")
    private fun installedPackageInfos(): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            context.packageManager.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)
        }

    /**
     * Opens the system uninstall dialog; there is no silent unrooted uninstall.
     *
     * Takes the caller's Activity context rather than using the application one
     * this class was constructed with. Launching an activity from the application
     * context is a background activity launch as far as the platform is concerned,
     * and the system uninstaller starts and immediately finishes without ever
     * showing its dialog - the button appears to do nothing at all.
     */
    fun uninstall(context: Context, packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
