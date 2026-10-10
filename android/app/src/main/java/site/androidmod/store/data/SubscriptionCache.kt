package site.androidmod.store.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The last subscription the server told us about.
 *
 * The Plan tab's view model is nav-scoped, so it is rebuilt on every visit and its
 * first frame is drawn before `/api/subscription` answers. With nothing to draw,
 * that frame read "Free" at paying subscribers for as long as the round trip took.
 * This keeps the previous answer so the first frame can be the right one.
 *
 * An envelope rather than a bare [Subscription] because there are three states and
 * not two: nothing cached, cached-and-free, cached-and-subscribed. A null from
 * [read] is the first; `SubscriptionEnvelope(subscription = null)` is the second.
 *
 * This is a display cache and nothing else. Downloads are authorised by the server,
 * which answers 402 whatever is stored here, so a stale value costs a wrong label
 * for one round trip and never an entitlement.
 */
interface SubscriptionCache {
    fun read(): SubscriptionEnvelope?
    fun write(envelope: SubscriptionEnvelope)
    fun clear()

    /** For wiring that has no storage, and for tests. */
    object None : SubscriptionCache {
        override fun read(): SubscriptionEnvelope? = null
        override fun write(envelope: SubscriptionEnvelope) = Unit
        override fun clear() = Unit
    }
}

/**
 * [SubscriptionCache] over app-private preferences.
 *
 * Not encrypted, unlike [TokenStore]: a plan name and a renewal date are shown on
 * screen to anyone holding the phone, and nothing here grants access to anything.
 */
class SubscriptionStore(context: Context) : SubscriptionCache {

    private val prefs = context.getSharedPreferences("subscription", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): SubscriptionEnvelope? {
        val stored = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        // A payload written by an older build may no longer parse. Treating that as
        // "nothing cached" degrades to the pre-cache behaviour instead of crashing
        // the Plan tab on the first launch after an upgrade.
        return runCatching { json.decodeFromString<SubscriptionEnvelope>(stored) }.getOrNull()
    }

    override fun write(envelope: SubscriptionEnvelope) {
        prefs.edit().putString(KEY_SNAPSHOT, json.encodeToString(envelope)).apply()
    }

    override fun clear() = prefs.edit().remove(KEY_SNAPSHOT).apply()

    private companion object {
        const val KEY_SNAPSHOT = "snapshot"
    }
}
