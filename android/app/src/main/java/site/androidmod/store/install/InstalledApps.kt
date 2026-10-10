package site.androidmod.store.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

data class InstalledApp(val packageName: String, val versionName: String)

/**
 * What is on the device. Needs QUERY_ALL_PACKAGES because the catalogue is
 * server-driven and the package set is not known at build time.
 */
class InstalledApps(private val context: Context) {

    fun find(packageName: String): InstalledApp? = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        InstalledApp(packageName, info.versionName.orEmpty())
    } catch (error: PackageManager.NameNotFoundException) {
        null
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
