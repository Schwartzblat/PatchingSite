package site.androidmod.store.generic

sealed interface RefreshHint {
    data object NewerRules : RefreshHint
    data class TokenExpiring(val daysLeft: Int) : RefreshHint
    data object TokenExpired : RefreshHint
    data object TokenUnknown : RefreshHint
}

const val REFRESH_BEFORE_EXPIRY_MILLIS = 30L * 24 * 60 * 60 * 1000

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** Why a patched app should be refreshed, most urgent first, or null. A null [latestBundleId] (offline) never says NewerRules. */
fun refreshHint(record: GenericRecord, latestBundleId: String?, nowMillis: Long): RefreshHint? {
    val expiresAt = record.tokenExpiresAt
    if (expiresAt != null) {
        val left = expiresAt - nowMillis
        if (left <= 0) return RefreshHint.TokenExpired
        if (left <= REFRESH_BEFORE_EXPIRY_MILLIS) return RefreshHint.TokenExpiring(((left + DAY_MILLIS - 1) / DAY_MILLIS).toInt())
    } else {
        return RefreshHint.TokenUnknown
    }
    return if (latestBundleId != null && latestBundleId != record.patchedBundleId) RefreshHint.NewerRules else null
}
