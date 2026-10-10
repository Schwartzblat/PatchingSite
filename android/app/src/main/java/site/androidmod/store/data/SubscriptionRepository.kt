package site.androidmod.store.data

class SubscriptionRepository(
    private val api: ApiClient,
    private val cache: SubscriptionCache = SubscriptionCache.None,
) {

    /**
     * The last answer [status] stored, or null if there has not been one yet.
     *
     * Read synchronously so a screen can seed its first frame with it. Callers must
     * still call [status]: this is what was true, not what is true.
     */
    fun cached(): SubscriptionEnvelope? = cache.read()

    suspend fun status(): Subscription? {
        val envelope: SubscriptionEnvelope = api.decode(api.get("/api/subscription"))
        // Written here rather than in a view model so every caller keeps the cache
        // warm - the catalogue asks for this on each visit to the Apps tab, which
        // is usually what has already refreshed it by the time Plan is opened.
        cache.write(envelope)
        return envelope.subscription
    }

    /** The server charges the product's own price; sending one would be ignored. */
    suspend fun checkoutUrl(): String {
        val response: UrlResponse = api.decode(api.post("/api/subscription/create-checkout", "{}"))
        return response.url
    }

    suspend fun billingPortalUrl(): String {
        val response: UrlResponse = api.decode(api.post("/api/subscription/billing-portal"))
        return response.url
    }

    suspend fun cancel() {
        api.post("/api/subscription/cancel")
    }

    suspend fun reactivate() {
        api.post("/api/subscription/reactivate")
    }

    /** Idempotent: answers "already confirmed" when the customer is already attached. */
    suspend fun confirmSuccess() {
        api.post("/api/subscription/confirm_success")
    }
}
