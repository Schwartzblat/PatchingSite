package site.androidmod.store.generic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshHintTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000_000_000_000L

    private fun record(expires: Long? = now + 90 * day) = GenericRecord(
        packageName = "com.example.app", label = "Example", versionName = "1", versionCode = 1L,
        originalSigners = setOf("aa"), apkNames = listOf("base.apk"),
        patchedBundleId = "b1", tokenExpiresAt = expires,
    )

    @Test fun expired() = assertEquals(RefreshHint.TokenExpired, refreshHint(record(now - 1), "b1", now))
    @Test fun expires_right_now_is_expired() = assertEquals(RefreshHint.TokenExpired, refreshHint(record(now), "b1", now))
    @Test fun exactly_30_days_left() = assertEquals(RefreshHint.TokenExpiring(30), refreshHint(record(now + 30 * day), "b1", now))
    @Test fun partial_day_rounds_up() = assertEquals(RefreshHint.TokenExpiring(1), refreshHint(record(now + 1), "b1", now))
    @Test fun thirty_one_days_same_bundle_is_null() = assertNull(refreshHint(record(now + 31 * day), "b1", now))
    @Test fun different_bundle_is_newer_rules() = assertEquals(RefreshHint.NewerRules, refreshHint(record(now + 31 * day), "b2", now))
    @Test fun null_latest_never_newer_rules() = assertNull(refreshHint(record(now + 31 * day), null, now))
    @Test fun no_expiry_is_unknown() = assertEquals(RefreshHint.TokenUnknown, refreshHint(record(null), "b1", now))
    @Test fun expiry_beats_newer_rules() = assertEquals(RefreshHint.TokenExpired, refreshHint(record(now - day), "b2", now))
    @Test fun unknown_beats_newer_rules() = assertEquals(RefreshHint.TokenUnknown, refreshHint(record(null), "b2", now))
}
