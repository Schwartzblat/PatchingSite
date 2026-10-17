package site.androidmod.store.patch

import android.content.Intent
import site.androidmod.store.install.InstallConfirmation
import site.androidmod.store.install.InstallFailure
import site.androidmod.store.install.InstallOutcome

/**
 * PatchService's install of a finished update, apart from the service so its rules are
 * testable on the JVM.
 *
 * Nobody may be watching: the user tapped Update and went back to WhatsApp during the
 * download. When Android then wants its "Do you want to update this app?" dialog, it can't
 * start it from the background, so the request also becomes a "Tap to install"
 * notification ([InstallConfirmation.notify]), and the install waits
 * [CONFIRM_TIMEOUT_MILLIS] for the user to come back to it rather than the three minutes a
 * Library install gets. The built file is the caller's to delete once this returns: by
 * then the outcome is known.
 */
internal object UpdateInstall {

    /** How long an update waits for the user to confirm it from the notification. */
    const val CONFIRM_TIMEOUT_MILLIS = 30L * 60 * 1000

    const val NOT_CONFIRMED = "The update wasn't confirmed. Tap Update to try again."

    /**
     * Installs through [install] with a confirmation that posts [prompt] and waits
     * [CONFIRM_TIMEOUT_MILLIS]; [clearPrompt] runs once the session has finished, however
     * it ended. Null when the update installed, else what the user is told.
     */
    suspend fun run(
        install: suspend (InstallConfirmation) -> InstallOutcome,
        prompt: (confirm: Intent) -> Unit,
        clearPrompt: () -> Unit,
    ): String? {
        val outcome = try {
            install(InstallConfirmation(CONFIRM_TIMEOUT_MILLIS, prompt))
        } finally {
            clearPrompt()
        }
        return failureMessage(outcome)
    }

    /** Null for success; a timeout is the user not having confirmed, anything else keeps the installer's words. */
    fun failureMessage(outcome: InstallOutcome): String? = when (outcome) {
        InstallOutcome.Success -> null
        is InstallOutcome.Failure -> if (outcome.reason == InstallFailure.TIMED_OUT) NOT_CONFIRMED else outcome.message
    }
}
