package site.androidmod.patch

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OriginalCertTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fx = GenericFixtures(tmp)
    private val entries = listOf("AndroidManifest.xml" to fx.baseManifest, "assets/a.txt" to "a".toByteArray())

    private fun sha1Upper(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02X".format(it) }

    private fun artifactoryZip(json: String?): File =
        zip(tmp.newFile(), if (json == null) entries else entries + ("assets/patcher/artifactory.json" to json.toByteArray()))

    @Test fun readsTheOnlySigner() {
        val apk = fx.devSigned(tmp.newFolder(), "a.apk", entries)
        assertEquals(sha1Upper(GenericFixtures.dev.cert.encoded), OriginalCert.of(apk, 36))
    }

    @Suppress("DEPRECATION")
    @Test fun twoSignersGiveNull() {
        val second = TestKeys.generate("second")
        val signed = File(tmp.newFolder(), "two.apk")
        com.android.apksig.ApkSigner.Builder(
            listOf(
                com.android.apksig.ApkSigner.SignerConfig.Builder("a", GenericFixtures.dev.privateKey, listOf(GenericFixtures.dev.cert)).build(),
                com.android.apksig.ApkSigner.SignerConfig.Builder("b", second.privateKey, listOf(second.cert)).build(),
            ),
        ).setInputApk(zip(tmp.newFile(), entries)).setOutputApk(signed).setMinSdkVersion(33).build().sign()
        assertNull(OriginalCert.of(signed, 36))
    }

    @Test fun unsignedGivesNull() {
        assertNull(OriginalCert.of(zip(tmp.newFile(), entries), 36))
    }

    @Test fun carriesThePreviousKey() {
        val apk = artifactoryZip("""{"ORIGINAL_CERT_SHA1":"c56abf7c6890113ed6654e381e2a1797da3e7f6a"}""")
        assertEquals("C56ABF7C6890113ED6654E381E2A1797DA3E7F6A", OriginalCert.carried(apk))
    }

    @Test fun nothingToCarry() {
        assertNull(OriginalCert.carried(artifactoryZip("""{"AD_SDKS_PRESENT":"x"}""")))
        assertNull(OriginalCert.carried(artifactoryZip("""{"ORIGINAL_CERT_SHA1":"xyz"}""")))
        assertNull(OriginalCert.carried(artifactoryZip(null)))
        assertNull(OriginalCert.carried(artifactoryZip("{not json")))
    }
}
