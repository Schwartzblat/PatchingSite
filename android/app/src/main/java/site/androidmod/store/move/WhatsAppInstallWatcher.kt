package site.androidmod.store.move

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PatternMatcher
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Puts a parked backup back the moment WhatsApp is installed from outside the
 * store: in practice by the Play Store, when going back to the official WhatsApp.
 * That install never passes MoveRepository.beforeInstall, and WhatsApp reads a
 * local backup only while it registers, which needs the SMS code. So there is time,
 * but nothing else in the store hears about the install.
 *
 * Registered in code for the life of the process (StoreApp), because Android 8
 * stopped delivering PACKAGE_ADDED to manifest receivers. So it is best effort,
 * not a guarantee:
 * - if the process is dead when the Play Store installs, nothing runs;
 * - since Android 14, a cached process (the store sitting in the background while
 *   the user is in the Play Store) gets the broadcast only once it is uncached,
 *   usually when the user comes back.
 * That is why the guide tells the user to come back before opening WhatsApp, and
 * why its step 4 offers "Put my backup back" when the backup is still parked. The
 * spare-phone run has to show how often it fires in time on its own.
 *
 * PACKAGE_ADDED also fires for an update, so a Play Store update that makes an
 * older official WhatsApp able to read the backup puts it back the same way, when
 * the store is running to hear it.
 */
class WhatsAppInstallWatcher(private val move: MoveRepository) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.data?.schemeSpecificPart != WHATSAPP_PACKAGE) return
        // Off the main thread: a put-back copies and hashes the databases.
        val pending = goAsync()
        Thread({
            try {
                move.putBackAfterInstall()?.let { Log.i(TAG, "Put back after install: ok=${it.ok} ${it.summary}") }
            } catch (error: Throwable) {
                Log.w(TAG, "Put back after install failed", error)
            } finally {
                pending.finish()
            }
        }, "whatsapp-put-back").start()
    }

    companion object {
        private const val TAG = "PatchedStore"

        fun register(context: Context, move: MoveRepository) {
            val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
                addDataScheme("package")
                addDataSchemeSpecificPart(WHATSAPP_PACKAGE, PatternMatcher.PATTERN_LITERAL)
            }
            // NOT_EXPORTED: only the system sends this, and system broadcasts still arrive.
            ContextCompat.registerReceiver(
                context, WhatsAppInstallWatcher(move), filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }
}
