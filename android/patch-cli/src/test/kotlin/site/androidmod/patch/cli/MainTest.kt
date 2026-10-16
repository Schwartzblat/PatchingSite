package site.androidmod.patch.cli

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MainTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String): File? =
        File(System.getProperty("patch.fixtures"), name).takeIf { it.isFile }

    @Test
    fun missingFlagIsUsageError() {
        assertEquals(2, run(arrayOf("--apk", "a.apk"), emptyMap()))
    }

    @Test
    fun unknownFlagIsUsageError() {
        assertEquals(2, run(arrayOf("--bogus", "x"), emptyMap()))
    }

    @Test
    fun passwordFlagValueIsRejected() {
        assertEquals(2, run(arrayOf("--keystore-pass", "secret"), emptyMap()))
    }

    @Test
    fun missingPasswordEnvIsUsageError() {
        val args = fullArgs(File("a"), File("b"), File("c"), File("k"), File("out"))
        assertEquals(2, run(args, emptyMap()))
    }

    private fun fullArgs(apk: File, bundle: File, record: File, key: File, out: File) = arrayOf(
        "--apk", apk.path, "--bundle", bundle.path, "--record", record.path,
        "--bundle-key", key.path, "--fastsigner", "/nonexistent/fastsigner",
        "--keystore", "/nonexistent/ks.p12", "--keystore-pass-env", "KS_PASS", "--out", out.path,
    )

    @Test
    fun passwordComesFromEnv() {
        val apk = fixture("com.whatsapp-2.26.40.72.apk")
        val bundle = fixture(".patcher-work/bundle-com.whatsapp-3.0.0.zip")
        val record = fixture(".patcher-work/wa-record.json")
        val key = File(System.getProperty("patch.bundleKey")).takeIf { it.isFile }
        assumeTrue(apk != null && bundle != null && record != null && key != null)
        val out = File(tmp.root, "out.apk")
        var seenPassword: String? = null
        var seenAlias: String? = null
        val code = run(fullArgs(apk!!, bundle!!, record!!, key!!, out) + arrayOf("--keystore-alias", "k"), mapOf("KS_PASS" to "hunter2")) { cfg ->
            seenPassword = String(cfg.password)
            seenAlias = cfg.alias
            site.androidmod.patch.ApkSigner { unsigned, signed -> unsigned.copyTo(signed, overwrite = true) }
        }
        assertEquals(0, code)
        assertEquals("hunter2", seenPassword)
        assertEquals("k", seenAlias)
        assertTrue(out.isFile)
    }
}
