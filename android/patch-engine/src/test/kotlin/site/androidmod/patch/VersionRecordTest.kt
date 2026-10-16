package site.androidmod.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class VersionRecordTest {
    private val minimal = """{"package":"p","version_name":"1","version_code":2,"apk_sha256":"a","dex_sha256":"d",
        "patcher_version":"3","artifacts":{"K":"v"},"missing_keys":[],"features_off":[],"supported":true,"source":"whatsapp.com"}"""

    @Test fun parsesRealRecord() {
        val f = fixtureFile(".patcher-work/wa-record.json"); assumeTrue(f != null)
        val r = VersionRecord.parse(f!!.readText())
        assertEquals("com.whatsapp", r.packageName)
        assertEquals(264007233L, r.versionCode)
        assertTrue(r.supported)
        assertEquals("X.Fuz", r.artifacts["UPGRADE_CHECK_CLASS_NAME"])
    }
    @Test fun ignoresSource() = assertEquals("p", VersionRecord.parse(minimal).packageName)
    @Test fun visibleTitlesSkipsHidden() {
        val f = Features(listOf("A"), emptyList(), listOf(
            Feature("settings_entry", "Settings shortcut", listOf("A")),
            Feature("firebase_params", "Push notifications", listOf("B"), visible = false),
            Feature("other", "Other", listOf("C"))))
        assertEquals(listOf("Settings shortcut"), f.visibleTitles(listOf("firebase_params", "settings_entry")))
    }
}
