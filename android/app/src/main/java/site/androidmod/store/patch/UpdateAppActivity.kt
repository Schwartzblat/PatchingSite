package site.androidmod.store.patch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import site.androidmod.store.MainActivity
import site.androidmod.store.StoreApp

private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

/** Where an outside request to update a package goes; see [updateAppRoute]. */
enum class UpdateAppRoute {
    /** Junk or malformed: nothing opens. */
    IGNORE,

    /** A real package the store doesn't update on the phone (not on-device, or not installed): the Library, nothing started. */
    OPEN_LIBRARY,

    /** An installed on-device app: the Library, which checks for its update and starts it. */
    UPDATE,
}

/**
 * Where an outside request to update [pkg] goes. The intent is exported, so the extra is
 * untrusted input: anything but a well-formed package name opens nothing, and only an
 * installed on-device app gets its update looked up and started. [installed] is not asked
 * about junk.
 */
fun updateAppRoute(pkg: String?, onDevice: Set<String>, installed: (String) -> Boolean): UpdateAppRoute = when {
    pkg == null || !PACKAGE_NAME.matches(pkg) -> UpdateAppRoute.IGNORE
    pkg in onDevice && installed(pkg) -> UpdateAppRoute.UPDATE
    else -> UpdateAppRoute.OPEN_LIBRARY
}

/** Whether an outside request to update [pkg] is one the store should start: see [updateAppRoute]. */
fun isValidUpdateRequest(pkg: String?, onDevice: Set<String>, installed: (String) -> Boolean): Boolean =
    updateAppRoute(pkg, onDevice, installed) == UpdateAppRoute.UPDATE

/**
 * The entry a patched app opens instead of its own updater (action
 * `site.androidmod.store.action.UPDATE_APP`, string extra `package`). It shows nothing:
 * the request is forwarded to [MainActivity] (see [updateAppRoute]) and this activity
 * finishes at once.
 */
class UpdateAppActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = StoreApp.instance.container
        // Another app's extras: an unknown Parcelable makes the read throw on older Android.
        val pkg = try {
            intent?.getStringExtra(EXTRA_PACKAGE)
        } catch (error: RuntimeException) {
            null
        }
        val route = updateAppRoute(pkg, container.settings.onDevicePackages) { container.installedApps.find(it) != null }
        if (route != UpdateAppRoute.IGNORE) {
            val open = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (route == UpdateAppRoute.UPDATE) {
                open.putExtra(UpdateWorker.EXTRA_OPEN_UPDATE, pkg)
            } else {
                open.putExtra(EXTRA_OPEN_LIBRARY, true)
            }
            startActivity(open)
        }
        // Before onResume, as Theme.NoDisplay requires.
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"

        /** MainActivity: open the Library and start nothing. */
        const val EXTRA_OPEN_LIBRARY = "open_library"
    }
}
