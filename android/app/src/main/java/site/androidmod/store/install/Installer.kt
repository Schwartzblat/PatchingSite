package site.androidmod.store.install

import android.content.Intent
import java.io.File

enum class InstallFailure { SIGNATURE_CONFLICT, UNTRUSTED_SIGNER, STORAGE, DOWNGRADE, ABORTED, TIMED_OUT, UNKNOWN }

sealed interface InstallOutcome {
    data object Success : InstallOutcome

    /**
     * [code] is the installer's own verdict - `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
     * and kin - when it named one. Null for failures raised before anything reached
     * an installer (an unreadable bundle, an untrusted signer), which have no code.
     */
    data class Failure(
        val message: String,
        val reason: InstallFailure,
        val code: String? = null,
    ) : InstallOutcome
}

/**
 * How an install answers Android asking for the user's go-ahead (the system's "Do you want
 * to install/update this app?" dialog), and how long it waits for the verdict.
 *
 * [DIALOG] is every install's default: InstallReceiver starts the dialog, and the wait
 * gives up after [DIALOG_TIMEOUT_MILLIS]. An install nobody is watching - PatchService's
 * updates - also passes [notify], which InstallReceiver hands the system's confirm intent so
 * a notification can carry it: the direct start is a background activity start then, which
 * Android blocks, and a tap on the notification is not. Its wait is as long as the user may
 * take to come back to that notification.
 */
class InstallConfirmation(
    val timeoutMillis: Long,
    /** Called on the main thread with Android's confirm intent, each time it asks; never for a session nobody waits on. */
    val notify: ((confirm: Intent) -> Unit)? = null,
) {
    companion object {
        /**
         * From commit to verdict. Generous, because it spans the user reading the
         * confirm dialog and Play Protect's "App scan recommended" prompt, and a
         * verdict can trail the dialog by ~20 seconds. Short enough that a dialog
         * Android never showed turns into "try again" within a few minutes, rather
         * than an Install button stuck on "Installing..." until the process dies.
         */
        const val DIALOG_TIMEOUT_MILLIS = 3 * 60 * 1000L

        val DIALOG = InstallConfirmation(DIALOG_TIMEOUT_MILLIS)
    }
}

interface Installer {
    /** False when this strategy cannot run here - e.g. root is not available. */
    val isAvailable: Boolean

    /** [confirmation] is used only by an installer that can need the user's go-ahead; see [InstallConfirmation]. */
    suspend fun install(file: File, confirmation: InstallConfirmation = InstallConfirmation.DIALOG): InstallOutcome

    /**
     * Installs separate APK files (a base and its splits) in one session; `apks[0]` is the base.
     * [extraTrusted] is trusted as a signer for this call only: a restore passes the original's
     * signers. An empty list, or two files with one name, is a Failure and creates no session.
     */
    suspend fun installApks(
        apks: List<File>,
        confirmation: InstallConfirmation = InstallConfirmation.DIALOG,
        extraTrusted: Set<String> = emptySet(),
    ): InstallOutcome
}

/**
 * Whether an install goes through [RootInstaller] rather than PackageInstaller.
 *
 * Debug builds only. The root path is unit-tested but has never completed on a
 * device (android/README.md, "How installing works"), so a release build always
 * uses PackageInstaller, whatever a preference left behind by a debug build says.
 *
 * [rootAvailable] is a lambda, and is consulted last, because answering it runs
 * `su`: on a rooted phone that pops the superuser manager's grant prompt. It must
 * only be asked when root would actually be used - never just because a screen
 * was opened, which is what Settings used to do.
 */
internal fun useRootInstaller(debugBuild: Boolean, silentInstall: Boolean, rootAvailable: () -> Boolean): Boolean =
    debugBuild && silentInstall && rootAvailable()

/**
 * Turns installer output into something a person can act on.
 *
 * Both strategies funnel through here so a root install and a PackageInstaller
 * install report the same failure the same way.
 */
object InstallErrors {

    // PackageInstaller reports "INSTALL_FAILED_X: detail" and `pm` reports
    // "Failure [INSTALL_FAILED_X: detail]"; a build that does not parse is
    // INSTALL_PARSE_FAILED_X. The code stops at the ':' or ']' that follows it.
    private val CODE = Regex("""INSTALL_(?:PARSE_)?FAILED_[A-Z0-9_]+""")

    /**
     * The one way raw installer output becomes a [InstallOutcome.Failure]. Every
     * installer-reported failure goes through here so none of them drops its code.
     */
    fun failure(raw: String?): InstallOutcome.Failure {
        val reason = classify(raw)
        return InstallOutcome.Failure(describe(reason, raw), reason, code(raw))
    }

    /**
     * The installer never reported back and the session was abandoned; see
     * InstallReceiver.await. Its own reason, never produced by [classify]: no
     * installer output says this, the wait simply ran out.
     */
    fun timedOut(): InstallOutcome.Failure =
        InstallOutcome.Failure(describe(InstallFailure.TIMED_OUT, null), InstallFailure.TIMED_OUT)

    /** The installer's own failure code in [raw], or null when it named none. */
    fun code(raw: String?): String? = raw?.let { CODE.find(it)?.value }

    /**
     * A few words for the install-finished toast. [describe] is the full
     * explanation, and the error banner shows it; a toast cannot carry it, because
     * Android 12+ cuts a text toast off after two lines.
     *
     * Null for UNKNOWN: "unknown" says nothing, so the toast shows the message instead.
     */
    fun summarize(reason: InstallFailure): String? = when (reason) {
        InstallFailure.SIGNATURE_CONFLICT -> "signature conflict"
        InstallFailure.UNTRUSTED_SIGNER -> "untrusted signature"
        InstallFailure.STORAGE -> "not enough storage"
        InstallFailure.DOWNGRADE -> "a newer version is installed"
        InstallFailure.ABORTED -> "cancelled"
        InstallFailure.TIMED_OUT -> "didn't finish"
        InstallFailure.UNKNOWN -> null
    }

    fun classify(raw: String?): InstallFailure {
        val text = raw.orEmpty().uppercase()
        return when {
            "UPDATE_INCOMPATIBLE" in text || "SIGNATURES DO NOT MATCH" in text ||
                "SHARED_USER_INCOMPATIBLE" in text -> InstallFailure.SIGNATURE_CONFLICT
            "INSUFFICIENT_STORAGE" in text -> InstallFailure.STORAGE
            "VERSION_DOWNGRADE" in text -> InstallFailure.DOWNGRADE
            "ABORTED" in text -> InstallFailure.ABORTED
            else -> InstallFailure.UNKNOWN
        }
    }

    fun describe(reason: InstallFailure, raw: String?): String = when (reason) {
        // Patched builds are signed with the project's key, so anything installed
        // from Play has to go first.
        InstallFailure.SIGNATURE_CONFLICT ->
            "A different build of this app is already installed and was signed by someone else. " +
                "Uninstall it first - that also deletes its data - then install this build."
        // Raised by SignatureVerifier BEFORE anything reaches the installer: the
        // downloaded build is not signed by the project's key, so it did not come
        // from the store. Distinct from SIGNATURE_CONFLICT, which is about an app
        // already on the device - here there is nothing to uninstall.
        InstallFailure.UNTRUSTED_SIGNER ->
            "This build is signed by a different key than the store app, so it was not installed. " +
                "It did not come from the store and may have been tampered with."
        InstallFailure.STORAGE -> "There is not enough free space on the device to install this build."
        InstallFailure.DOWNGRADE ->
            "A newer version of this app is installed. Uninstall it before installing this older build."
        InstallFailure.ABORTED -> "The install was cancelled."
        // Says what to do rather than what went wrong: the likely cause - the
        // confirm dialog was blocked because the app was in the background - is
        // not something the user can see or needs to understand.
        InstallFailure.TIMED_OUT -> "Installation didn't finish. Tap Install to try again."
        InstallFailure.UNKNOWN -> "The install failed: ${raw.orEmpty().ifBlank { "no reason given" }}"
    }
}
