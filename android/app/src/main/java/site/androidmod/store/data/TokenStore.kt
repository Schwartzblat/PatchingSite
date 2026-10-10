package site.androidmod.store.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/** The one thing repositories need from a token store, so tests can supply their own. */
interface TokenHolder {
    var token: String?
}

/**
 * Where [TokenStore] keeps the token between launches. The only real
 * implementation is [EncryptedTokenPersistence]; the interface is the seam that
 * lets the recovery logic below be tested without an Android keystore.
 *
 * Both methods may throw: the keystore behind them can fail at any call, not just
 * when it is first opened.
 */
interface TokenPersistence {
    fun read(): String?
    fun write(value: String?)
}

/**
 * Holds the bearer token. It is a long-lived (~30-day), non-revocable JWT, so it
 * is encrypted at rest and NEVER written to disk in the clear.
 *
 * The Android keystore behind the encryption can be damaged - a vendor keystore
 * bug, a lock-screen change that invalidates keys on some devices, or a prefs file
 * that outlived the key it was encrypted with. How this store behaves then:
 *
 * - Opening fails: it is tried once more as it is, because the keystore also
 *   throws transient errors (around a keystore daemon restart, or just after boot
 *   on some devices), and wiping a good store over one of those would sign the
 *   user out for nothing. Failing twice means the store is damaged: the prefs file
 *   and its master key are deleted and it is opened from scratch. That loses
 *   nothing - a store that cannot be opened holds no token anyone can read - and
 *   it heals the common case, a file whose key is gone.
 * - It still fails: the token lives in memory for this process. Sign-in works and
 *   the app is usable; the user signs in again after the next launch. This used to
 *   drop the write silently instead, so `me()` went out with no token, answered
 *   401, and the user could never sign in at all.
 * - A stored value cannot be decrypted: it is discarded (file and key reset) and
 *   read as "signed out", instead of the exception crashing every launch until the
 *   app's data was cleared.
 *
 * A cleartext fallback was considered and rejected: a non-revocable credential
 * readable on a rooted or seized-unlocked device is a far worse failure than
 * having to sign in again.
 */
class TokenStore internal constructor(
    private val open: () -> TokenPersistence,
    /** Deletes the persisted file and the key it is encrypted with. */
    private val reset: () -> Unit,
    private val warn: (String, Throwable) -> Unit,
) : TokenHolder {

    constructor(context: Context) : this(
        open = { EncryptedTokenPersistence.open(context) },
        reset = { EncryptedTokenPersistence.reset(context) },
        warn = { message, error -> Log.w(TAG, message, error) },
    )

    // The getter runs on OkHttp's calling thread for every request while the UI
    // can be signing in or out, so all state below is read and written under this.
    private val lock = Any()

    /**
     * Opened lazily, on the first read or write. The constructor runs in
     * Application.onCreate on the main thread, before the first frame, and a
     * keystore round trip - let alone a reset - does not belong there. The first
     * access is AuthRepository.restoreSession at launch, which runs on
     * Dispatchers.IO for exactly this reason; every later read is an in-memory
     * decrypt.
     */
    private var persistence: TokenPersistence? = null

    /**
     * Set once persistence has failed for good. Latched for the rest of the
     * process, because the getter runs on every API call and re-attempting a
     * broken keystore each time would put a keystore round trip on each of them.
     */
    private var memoryOnly = false
    private var inMemory: String? = null

    override var token: String?
        get() = synchronized(lock) {
            val storage = storage() ?: return inMemory
            try {
                storage.read()
            } catch (error: Throwable) {
                warn("The stored token could not be decrypted; discarding it", error)
                discard()
                null
            }
        }
        set(value) = synchronized(lock) {
            if (!persist(value)) {
                memoryOnly = true
                inMemory = value
            }
        }

    fun clear() {
        token = null
    }

    /** The open store, or null once the process has fallen back to memory. */
    private fun storage(): TokenPersistence? {
        if (memoryOnly) return null
        persistence?.let { return it }
        return openWithRecovery().also { opened ->
            persistence = opened
            if (opened == null) memoryOnly = true
        }
    }

    private fun openWithRecovery(): TokenPersistence? {
        repeat(OPEN_ATTEMPTS_BEFORE_RESET) {
            try {
                return open()
            } catch (error: Throwable) {
                warn("Encrypted token storage failed to open", error)
            }
        }
        // Failed every time: damaged, not a hiccup. Start it over.
        resetQuietly()
        return try {
            open()
        } catch (error: Throwable) {
            warn("Encrypted token storage is unavailable; the token will be kept in memory only", error)
            null
        }
    }

    /**
     * Two attempts, the second on a store rebuilt from nothing. False when both
     * fail - by which point [discard] has already removed whatever an older write
     * left behind, so a previous account's token cannot resurface next launch.
     */
    private fun persist(value: String?): Boolean {
        repeat(WRITE_ATTEMPTS) {
            val storage = storage() ?: return false
            try {
                storage.write(value)
                return true
            } catch (error: Throwable) {
                warn("The token could not be written to encrypted storage", error)
                discard()
            }
        }
        return false
    }

    /** Forget the open store and wipe it; the next access opens a fresh one. */
    private fun discard() {
        persistence = null
        resetQuietly()
    }

    private fun resetQuietly() {
        try {
            reset()
        } catch (error: Throwable) {
            warn("Could not reset encrypted token storage", error)
        }
    }

    private companion object {
        const val TAG = "PatchedStore"
        const val WRITE_ATTEMPTS = 2
        const val OPEN_ATTEMPTS_BEFORE_RESET = 2
    }
}

/** The token in EncryptedSharedPreferences, under a MasterKey in the Android keystore. */
private class EncryptedTokenPersistence(private val prefs: SharedPreferences) : TokenPersistence {

    override fun read(): String? = prefs.getString(KEY_TOKEN, null)

    override fun write(value: String?) {
        // The editor encrypts inside putString, so a keystore failure throws here,
        // synchronously, where TokenStore can catch it. apply() only defers the
        // disk write of bytes that are already encrypted.
        prefs.edit().apply {
            if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value)
        }.apply()
    }

    companion object {
        private const val PREFS_FILE = "auth"

        /** A cleartext store an older build fell back to. Removed whenever the store opens. */
        private const val LEGACY_PLAINTEXT_FILE = "auth-plain"
        private const val KEY_TOKEN = "access_token"

        // security-crypto 1.1.0 deprecates MasterKey and EncryptedSharedPreferences
        // upstream, with no drop-in replacement at this version, so these warnings
        // are not actionable. Suppressed narrowly over this function alone, so a
        // genuine deprecation elsewhere stays visible.
        @Suppress("DEPRECATION")
        fun open(context: Context): TokenPersistence {
            // Removed on every open, not only on a reset: a device where an old
            // build fell back to it and whose keystore works now would otherwise
            // keep a cleartext token forever. Deleting a missing file is a no-op.
            context.deleteSharedPreferences(LEGACY_PLAINTEXT_FILE)
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedTokenPersistence(
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            )
        }

        /**
         * Deletes the prefs file AND the master key. Both, because the usual damage
         * is a file encrypted under a key the keystore no longer has: deleting only
         * the file would re-create it under that same broken key.
         *
         * The master key is androidx.security's default alias, shared by every
         * EncryptedSharedPreferences and EncryptedFile in the app. This app has
         * exactly one, this store, so nothing else is encrypted under it.
         */
        @Suppress("DEPRECATION")
        fun reset(context: Context) {
            // deleteSharedPreferences also evicts the process-wide SharedPreferences
            // cache, so the next open reads a new file rather than the broken one.
            context.deleteSharedPreferences(PREFS_FILE)
            KeyStore.getInstance("AndroidKeyStore")
                .apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
    }
}
