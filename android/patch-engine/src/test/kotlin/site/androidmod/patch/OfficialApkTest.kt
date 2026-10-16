package site.androidmod.patch

import com.android.apksig.ApkSigner
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfficialApkTest {
    @get:Rule val tmp = TemporaryFolder()

    private val v31 = "fb920d381bee1b2093f27dc8f13d994da629dc91887d0529b35c9a2dc4f4a6c2"
    private val v3 = "3987d043d10aefaf5a8710b3671418fe57e0e19b653c9df82558feb5ffce5d44"

    private fun tinyApk(): File {
        val am = AndroidManifestBlock.empty()
        am.packageName = "com.tiny"
        am.setMinSdkVersion(30)
        am.refreshFull()
        return zip(
            tmp.newFile("tiny.apk"),
            listOf("AndroidManifest.xml" to am.bytes, "classes.dex" to "dex\n035\u0000x".toByteArray()),
        )
    }

    @Test fun realWhatsAppIsOfficial() {
        val wa = fixtureFile("com.whatsapp-2.26.40.72.apk")
        assumeTrue(wa != null)
        OfficialApk.verify(wa!!, listOf(v31, v3))
        assertTrue(v31 in OfficialApk.signerCertSha256s(wa))
    }

    @Suppress("DEPRECATION")
    @Test fun resignedCopyRefused() {
        val unsigned = tinyApk()
        val key = TestKeys.generate()
        val signed = tmp.newFile("signed.apk")
        ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("t", key.privateKey, listOf(key.cert)).build()))
            .setInputApk(unsigned).setOutputApk(signed).setMinSdkVersion(30).build().sign()
        assertEquals(1, OfficialApk.signerCertSha256s(signed).size)
        val e = runCatching { OfficialApk.verify(signed, listOf(v31)) }.exceptionOrNull()
        assertTrue(e is PatchException.NotOfficial)
    }

    @Test fun unsignedRefused() {
        val e = runCatching { OfficialApk.verify(tinyApk(), listOf(v31)) }.exceptionOrNull()
        assertTrue(e is PatchException.NotOfficial)
    }
}
