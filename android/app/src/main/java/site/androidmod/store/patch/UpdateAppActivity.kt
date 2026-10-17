package site.androidmod.store.patch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import site.androidmod.store.MainActivity
import site.androidmod.store.StoreApp

private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

/**
 * Whether an outside request to update [pkg] is one the store should act on: a well-formed
 * package name of an on-device app that is installed. The intent is exported, so the
 * extra is untrusted input.
 */
fun isValidUpdateRequest(pkg: String?, onDevice: Set<String>, installed: (String) -> Boolean): Boolean =
    pkg != null && PACKAGE_NAME.matches(pkg) && pkg in onDevice && installed(pkg)

/**
 * The entry a patched app opens instead of its own updater (action
 * `site.androidmod.store.action.UPDATE_APP`, string extra `package`). It shows nothing:
 * a valid request is forwarded to [MainActivity] and this activity finishes at once.
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
        if (isValidUpdateRequest(pkg, container.settings.onDevicePackages) { container.installedApps.find(it) != null }) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(UpdateWorker.EXTRA_OPEN_UPDATE, pkg)
            )
        }
        // Before onResume, as Theme.NoDisplay requires.
        finish()
    }

    private companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
