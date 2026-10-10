package site.androidmod.store.data

/** SubscriptionStore needs a Context; callers only need somewhere to put a value. */
class FakeSubscriptionCache : SubscriptionCache {
    var stored: SubscriptionEnvelope? = null
    var cleared = 0

    override fun read(): SubscriptionEnvelope? = stored

    override fun write(envelope: SubscriptionEnvelope) {
        stored = envelope
    }

    override fun clear() {
        stored = null
        cleared++
    }
}
