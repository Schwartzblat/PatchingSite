package site.androidmod.store

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import site.androidmod.store.patch.PatchService
import site.androidmod.store.patch.UpdateAppActivity
import site.androidmod.store.patch.UpdateWorker
import site.androidmod.store.ui.AppNavigation
import site.androidmod.store.ui.NavRequest
import site.androidmod.store.ui.NavigationRequests
import site.androidmod.store.ui.requestFrom
import site.androidmod.store.ui.theme.StoreTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 36 draws edge to edge whether we ask for it or not, so the only
        // choice left is whether the bars are styled deliberately. Both are pinned
        // dark with light icons because this app has no light theme; the default
        // would adapt to the system setting and put dark icons on our dark bars.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Not on a recreation: the intent is the same one, already handled.
        if (savedInstanceState == null) handleRequest(intent)
        setContent {
            StoreTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRequest(intent)
    }

    /** A notification tap or the patched app's update button: where in the store to go. */
    private fun handleRequest(intent: Intent?) {
        intent ?: return
        // The extras may come from another app: on older Android reading one unparcels all
        // of them, and an unknown Parcelable throws. That is "no request", not a crash.
        val request = try {
            requestFrom(
                intent.getStringExtra(UpdateWorker.EXTRA_OPEN_UPDATE),
                intent.getBooleanExtra(UpdateWorker.EXTRA_OPEN_MOVE, false),
                intent.flags,
                library = intent.getBooleanExtra(UpdateAppActivity.EXTRA_OPEN_LIBRARY, false),
                generic = intent.getStringExtra(PatchService.EXTRA_OPEN_GENERIC),
            )
        } catch (error: RuntimeException) {
            null
        }
        if (request != null) NavigationRequests.request(request)
    }

    override fun onStart() {
        super.onStart()
        // Every open of the store, on any screen, is a chance to save the backup the
        // guide accepted for step 2 before WhatsApp is removed some other way than
        // step 4: on 2026-10-10 it was removed from Settings with nothing saved, and
        // the backup went with it. A plain thread, as WhatsAppInstallWatcher uses: no
        // screen waits on it, and it returns at once when there is nothing to do.
        val move = StoreApp.instance.container.move
        Thread({
            try {
                move.track()
            } catch (error: Throwable) {
                Log.w("PatchedStore", "Saving the WhatsApp backup failed", error)
            }
        }, "whatsapp-save-backup").start()
    }
}
