package site.androidmod.store.data

import android.content.Context
import site.androidmod.store.BuildConfig

/** User-visible preferences. Nothing secret lives here. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * The server the client talks to - and so the host it trusts as the source of
     * installable code.
     *
     * Overridable ONLY in debug builds, so a developer can point the app at a dev
     * machine. In release the override is ignored end to end: a stored value is
     * never read and never written, so a user - or someone who talks a user into
     * pasting a URL - cannot repoint a shipped build at an arbitrary host. Release
     * is always pinned to the baked-in BuildConfig.DEFAULT_BASE_URL.
     */
    var baseUrl: String
        get() = if (BuildConfig.DEBUG) {
            prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
                ?: BuildConfig.DEFAULT_BASE_URL
        } else {
            BuildConfig.DEFAULT_BASE_URL
        }
        // Persist the override on the debug path only; release never writes it.
        set(value) {
            if (BuildConfig.DEBUG) {
                prefs.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()
            }
        }

    /**
     * Install with root, without the system dialog. Debug builds only, like
     * [baseUrl]: the root path has never completed on a device, so release neither
     * reads nor writes it - a value stored by a debug build installed over the same
     * package cannot switch a release onto it. Honoured only when the device
     * actually grants root; see Container.installer().
     */
    var silentInstall: Boolean
        get() = BuildConfig.DEBUG && prefs.getBoolean(KEY_SILENT_INSTALL, false)
        set(value) {
            if (BuildConfig.DEBUG) prefs.edit().putBoolean(KEY_SILENT_INSTALL, value).apply()
        }

    /**
     * True between launching Stripe checkout and confirming it.
     *
     * It lives here rather than in SubscriptionViewModel's state because that view
     * model is nav-scoped: if the Activity is destroyed while the Custom Tab is in
     * front - low memory, or "Don't keep activities" - the in-memory flag is gone,
     * the return guard early-returns, and confirm_success is never called. There is
     * NO Stripe webhook in this backend, so confirm_success is the only thing that
     * ever writes customer_id onto the user row: losing that flag means a charged
     * customer with an account that does not know it.
     */
    var awaitingCheckout: Boolean
        get() = prefs.getBoolean(KEY_AWAITING_CHECKOUT, false)
        // commit(), not apply(): apply() writes to disk asynchronously, so a process
        // killed in the milliseconds between setting this and the Custom Tab coming
        // up would lose the flag - the exact failure this field exists to prevent,
        // in a smaller window. One boolean on a user action; the synchronous write
        // costs nothing here.
        set(value) {
            prefs.edit().putBoolean(KEY_AWAITING_CHECKOUT, value).commit()
        }

    /**
     * True from the moment the store hands its own update to an installer until
     * the updated build's MY_PACKAGE_REPLACED receiver consumes it.
     *
     * The flag is what separates "we updated ourselves" from any other replace -
     * `gradlew installDebug`, or a build installed by hand from the website - so
     * only the first posts the "tap to reopen" notification. commit(), not
     * apply(), for the same reason as awaitingCheckout: a successful install kills
     * this process, and an asynchronous write can die with it.
     */
    var pendingSelfUpdate: Boolean
        get() = prefs.getBoolean(KEY_PENDING_SELF_UPDATE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PENDING_SELF_UPDATE, value).commit()
        }

    /**
     * True from just before WhatsApp's backup is parked until the move is finished.
     *
     * The move engine derives everything from disk and stores nothing, which is
     * right while it can read the disk. Without All files access it can read
     * nothing, so a half-done move looks exactly like no move at all, and a plain
     * install would let WhatsApp register without its backup. This hint is what is
     * left to say "stop and ask for access first" in that state. While access is
     * granted the engine's answer wins and clears it; see MoveRepository.status.
     *
     * commit(), not apply(): it is set immediately before the park, and a process
     * killed with the write still pending would lose the one record that a move
     * had started.
     */
    var whatsAppMoveActive: Boolean
        get() = prefs.getBoolean(KEY_WHATSAPP_MOVE_ACTIVE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WHATSAPP_MOVE_ACTIVE, value).commit()
        }

    /**
     * When the user last tapped Start on the chat-move guide, 0 when never or once
     * the move finished. A backup only counts for the move when it is newer than
     * this: tested on a device, a backup made minutes before the guide was opened
     * was ticked off as fresh, and every message after it would have been lost.
     *
     * Stored rather than held in memory because the user makes that backup in
     * WhatsApp, with this app in the background, where Android may kill it. A
     * remembered start time would then be lost, and the guide would keep asking
     * for a newer backup than the one just made.
     */
    var whatsAppMoveStartedAt: Long
        get() = prefs.getLong(KEY_WHATSAPP_MOVE_STARTED_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_WHATSAPP_MOVE_STARTED_AT, value).apply()
        }

    /**
     * Which way the move under way is going: true when it is going back to the
     * official WhatsApp. Read only while [whatsAppMoveActive] is set and file access
     * is missing, which is the one state where the manifest that records it cannot
     * be read. A move begun before this existed has it unset, which reads as "into
     * ours": right, since that was the only direction there was.
     *
     * commit() for the same reason as whatsAppMoveActive: it is written just before
     * the park.
     */
    var whatsAppMoveToOfficial: Boolean
        get() = prefs.getBoolean(KEY_WHATSAPP_MOVE_TO_OFFICIAL, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WHATSAPP_MOVE_TO_OFFICIAL, value).commit()
        }

    /**
     * When the store last saw the WhatsApp being left still installed, after the
     * user's Start; 0 when never, or once the move ended. The guide says "Your
     * backup was deleted" only when this is later than the Start: otherwise anyone
     * who opened the guide with no WhatsApp at all would be told their backup was
     * lost.
     *
     * apply(), not commit(): losing it costs that panel, never a backup.
     */
    var whatsAppMoveSeenInstalledAt: Long
        get() = prefs.getLong(KEY_WHATSAPP_MOVE_SEEN_INSTALLED_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_WHATSAPP_MOVE_SEEN_INSTALLED_AT, value).apply()
        }

    /**
     * Packages the server's catalogue marks `on_device`, as of the last catalogue
     * that loaded. Kept so the store knows offline which apps it patches itself.
     */
    var onDevicePackages: Set<String>
        get() = prefs.getStringSet(KEY_ON_DEVICE_PACKAGES, null)?.toSet().orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_ON_DEVICE_PACKAGES, value.toSet()).apply()
        }

    // Debug-only, mirroring baseUrl: release keeps no override to reset.
    fun resetBaseUrl() {
        if (BuildConfig.DEBUG) prefs.edit().remove(KEY_BASE_URL).apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_ON_DEVICE_PACKAGES = "on_device_packages"
        const val KEY_SILENT_INSTALL = "silent_install"
        const val KEY_AWAITING_CHECKOUT = "awaiting_checkout"
        const val KEY_PENDING_SELF_UPDATE = "pending_self_update"
        const val KEY_WHATSAPP_MOVE_ACTIVE = "whatsapp_move_active"
        const val KEY_WHATSAPP_MOVE_STARTED_AT = "whatsapp_move_started_at"
        const val KEY_WHATSAPP_MOVE_TO_OFFICIAL = "whatsapp_move_to_official"
        const val KEY_WHATSAPP_MOVE_SEEN_INSTALLED_AT = "whatsapp_move_seen_installed_at"
    }
}
