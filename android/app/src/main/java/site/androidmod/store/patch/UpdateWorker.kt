package site.androidmod.store.patch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import site.androidmod.store.MainActivity
import site.androidmod.store.R
import site.androidmod.store.StoreApp
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.needsSubscription
import site.androidmod.store.move.WHATSAPP_PACKAGE
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Every six hours: asks the server what is newest for each on-device app the user has
 * installed and tells them, once per offer, when there is something to update to.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = StoreApp.instance.container
        val settings = container.settings
        val packages = settings.onDevicePackages
        if (packages.isEmpty() || container.tokens.token == null) return Result.success()

        val userCert = container.signingKeys.cached()?.certSha256
            ?: try {
                container.signingKeys.get().certSha256
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return Result.success()
            }
        val sharedCert = container.move.ownSignature

        for (pkg in packages) {
            val installed = container.installedApps.find(pkg) ?: continue
            val latest = try {
                container.patchApi.latest(pkg)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                if (error.needsSubscription) return Result.success()
                continue
            } catch (error: Exception) {
                continue
            }
            val patchInfo = withContext(Dispatchers.IO) { installed.sourceDir?.let { readPatchInfo(File(it)) } }
            val offer = updateOffer(installed, patchInfo, latest, userCert, sharedCert)
            if (offer == UpdateOffer.None) continue
            val key = offerKey(pkg, latest, offer)
            if (key in settings.notifiedOffers) continue

            val titles = (offer as? UpdateOffer.PatchUpdate)?.let { patch ->
                withContext(Dispatchers.IO) { container.bundles.cachedFeatures(pkg, latest.patcherVersion) }
                    ?.visibleTitles(patch.featuresBack)
            }.orEmpty()
            val name = if (pkg == WHATSAPP_PACKAGE) "WhatsApp" else container.appNames[pkg] ?: pkg
            val text = updateNotificationText(name, offer, titles) ?: continue
            if (post(pkg, offer, text.title, text.body)) settings.notifiedOffers = settings.notifiedOffers + key
        }
        return Result.success()
    }

    private fun post(pkg: String, offer: UpdateOffer, title: String, body: String): Boolean {
        val context = applicationContext
        val notifications = NotificationManagerCompat.from(context)
        // POST_NOTIFICATIONS denied or notifications off: leave the offer un-remembered.
        if (!notifications.areNotificationsEnabled()) return false
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val move = offer == UpdateOffer.NeedsMove
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (move) intent.putExtra(EXTRA_OPEN_MOVE, true) else intent.putExtra(EXTRA_OPEN_UPDATE, pkg)
        val id = notificationId(pkg, move)
        val open = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // areNotificationsEnabled checked above
        notifications.notify(id, notification)
        return true
    }

    companion object {
        const val CHANNEL_ID = "app_updates"
        const val WORK_NAME = "patch-update-check"
        const val EXTRA_OPEN_UPDATE = "open_update"
        const val EXTRA_OPEN_MOVE = "open_move"
        private const val ID_BASE = 0x5500_0000

        /** Distinct per package and kind, so a move and an update for one app do not replace each other. */
        private fun notificationId(pkg: String, move: Boolean) =
            ID_BASE + ((pkg.hashCode() and 0xFFFF) shl 1) + if (move) 1 else 0

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
