package site.androidmod.store.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import site.androidmod.store.MainActivity
import site.androidmod.store.R
import site.androidmod.store.StoreApp

/**
 * Tells the user the self-update landed.
 *
 * Replacing its own APK kills this app, and Android does not relaunch it: from
 * the user's side, tapping Update makes the store vanish, which reads as a crash.
 * MY_PACKAGE_REPLACED is delivered to the NEW build, so this runs in the updated
 * code. A notification rather than starting MainActivity: a receiver is a
 * background context, and background activity launches are blocked.
 */
class UpdatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = StoreApp.instance.container
        // Only an update this app started itself; see SettingsStore.pendingSelfUpdate.
        if (!container.settings.pendingSelfUpdate) return
        container.settings.pendingSelfUpdate = false

        val notifications = NotificationManagerCompat.from(context)
        // Denied POST_NOTIFICATIONS: nothing to show, and nothing else to do - the
        // update itself already succeeded.
        if (!notifications.areNotificationsEnabled()) return

        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val installed = container.installedBuild()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Patched Store updated")
            .setContentText("${versionLabel(installed.versionName, installed.versionCode)} is installed. Tap to open.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Guarded above by areNotificationsEnabled; the lint check cannot see that.
        @Suppress("MissingPermission")
        notifications.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "updates"
        const val NOTIFICATION_ID = 1002
    }
}
