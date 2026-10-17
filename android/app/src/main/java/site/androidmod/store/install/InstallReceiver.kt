package site.androidmod.store.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives PackageInstaller's verdict and hands it back to the coroutine waiting on
 * that session. Sessions are keyed because more than one install can be pending.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Nobody waiting means the installer timed out and abandoned this
            // session (see [await]); a dialog now would confirm an install that can
            // no longer happen, long after the user was told to try again.
            val confirmation = confirmationFor(sessionId) ?: return
            // The system wants to show its confirmation dialog. Launching it from a
            // receiver needs NEW_TASK.
            // getParcelableExtra(String, Class) is API 33+; minSdk here is 30, so the
            // unguarded call is a lint NewApi error and a NoSuchMethodError on 30-32.
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            }
            if (confirm == null) {
                // The framework always populates this extra for this status, so this
                // is defensive - but returning without it would leave the caller
                // awaiting a result that can now never arrive.
                deliver(
                    sessionId,
                    InstallOutcome.Failure(
                        "Android did not supply an install confirmation dialog.",
                        InstallFailure.UNKNOWN,
                    ),
                )
                return
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Before the start, which Android blocks while the app is in the background:
            // an install nobody is watching is confirmed from its notification instead.
            confirmation.notify?.let { notify ->
                runCatching { notify(confirm) }.onFailure { Log.w(TAG, "Could not post the install prompt", it) }
            }
            context.startActivity(confirm)
            return
        }

        deliver(
            sessionId,
            if (status == PackageInstaller.STATUS_SUCCESS) {
                InstallOutcome.Success
            } else {
                InstallErrors.failure(message)
            },
        )
    }

    companion object {
        const val ACTION = "site.androidmod.store.INSTALL_RESULT"
        private const val TAG = "PatchedStore"

        /** One committed session's waiting installer, and how it wants the user asked. */
        private class Waiter(val result: CompletableDeferred<InstallOutcome>, val confirmation: InstallConfirmation)

        private val pending = ConcurrentHashMap<Int, Waiter>()

        fun awaiting(
            sessionId: Int,
            confirmation: InstallConfirmation = InstallConfirmation.DIALOG,
        ): CompletableDeferred<InstallOutcome> =
            CompletableDeferred<InstallOutcome>().also { pending[sessionId] = Waiter(it, confirmation) }

        fun forget(sessionId: Int) {
            pending.remove(sessionId)
        }

        /** Whether an installer is still waiting on [sessionId]. */
        fun isPending(sessionId: Int): Boolean = pending.containsKey(sessionId)

        /** How [sessionId]'s installer wants a confirmation request answered; null when nobody waits on it. */
        fun confirmationFor(sessionId: Int): InstallConfirmation? = pending[sessionId]?.confirmation

        /** Claims [sessionId] for giving up; false when the receiver delivered (or is delivering) first. */
        private fun giveUp(sessionId: Int, result: CompletableDeferred<InstallOutcome>): Boolean {
            val waiter = pending[sessionId]?.takeIf { it.result === result } ?: return false
            return pending.remove(sessionId, waiter)
        }

        /**
         * Hands [outcome] to whoever awaits [sessionId]. False when nobody does any
         * more - a verdict that arrived after [await] gave up - and it is dropped:
         * the user has already been told to try again, and a late verdict must not
         * rewrite that. remove() makes delivery and giving up mutually exclusive.
         */
        fun deliver(sessionId: Int, outcome: InstallOutcome): Boolean =
            pending.remove(sessionId)?.result?.complete(outcome) ?: false

        /**
         * Waits for the verdict on a committed session, for at most [timeoutMillis].
         *
         * Without a limit this could wait forever. PackageInstaller asks for the
         * user's confirmation by sending STATUS_PENDING_USER_ACTION to this
         * receiver, which starts the system dialog - but if the user has left the
         * app while the build was staging, Android blocks that background activity
         * start, no dialog appears, and no verdict ever comes: the button said
         * "Installing..." until the process died. (An install that passed
         * [InstallConfirmation.notify] has a notification for that, and a longer wait.)
         *
         * On timeout the session is [abandon]ed and the result says what to do.
         * Giving up is claimed atomically - [giveUp]'s `remove(key, value)` - so exactly one
         * of two things happens: the receiver delivers first and that verdict is
         * returned, or this gives up first and any later verdict finds nobody
         * waiting and is dropped. A late result cannot flip a failure the user has
         * already seen, and a late PENDING_USER_ACTION does not open a dialog for a
         * session that no longer exists.
         */
        suspend fun await(
            sessionId: Int,
            result: CompletableDeferred<InstallOutcome>,
            timeoutMillis: Long,
            abandon: () -> Unit,
        ): InstallOutcome {
            val verdict = try {
                withTimeoutOrNull(timeoutMillis) { result.await() }
            } catch (error: CancellationException) {
                // The caller went away - its view model was cleared when the user
                // left the app mid-install - but the session is already committed.
                // Give it up the same way a timeout does, or a confirmation request
                // arriving later opens a dialog for an install nobody is watching.
                if (giveUp(sessionId, result)) abandon()
                throw error
            }
            verdict?.let { return it }
            if (!giveUp(sessionId, result)) {
                // The receiver claimed the verdict in the instant before the
                // timeout fired and is completing it now.
                return result.await()
            }
            abandon()
            return InstallErrors.timedOut()
        }
    }
}
