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
    fun emptyPasswordEnvIsUsageError() {
        val args = fullArgs(File("a"), File("b"), File("c"), File("k"), File("out"))
        assertEquals(2, run(args, mapOf("KS_PASS" to "")))
    }

    private fun inputs(record: String, bundleBytes: ByteArray = byteArrayOf(1, 2, 3), key: String = "garbage"): Array<String> {
        val apk = tmp.newFile().also { it.writeText("x") }
        val bundle = tmp.newFile().also { it.writeBytes(bundleBytes) }
        val rec = tmp.newFile().also { it.writeText(record) }
        val k = tmp.newFile().also { it.writeText(key) }
        return fullArgs(apk, bundle, rec, k, File(tmp.root, "out.apk"))
    }

    private val validRecord = """{"package":"p","version_name":"1","version_code":1,"apk_sha256":"00","dex_sha256":"00",""" +
        """"patcher_version":"3","artifacts":{},"missing_keys":[],"features_off":[],"supported":true}"""

    @Test
    fun malformedRecordExitsOne() {
        assertEquals(1, run(inputs("{not json"), mapOf("KS_PASS" to "pw")))
    }

    @Test
    fun badBundleKeyExitsOne() {
        assertEquals(1, run(inputs(validRecord), mapOf("KS_PASS" to "pw")))
    }

    @Test
    fun badBundleExitsOne() {
        val key = File(System.getProperty("patch.bundleKey")).takeIf { it.isFile }
        assumeTrue(key != null)
        val args = inputs(validRecord, key = key!!.readText())
        assertEquals(1, run(args, mapOf("KS_PASS" to "pw")))
    }

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
        var seenKeyPass: String? = null
        val code = run(fullArgs(apk!!, bundle!!, record!!, key!!, out) + arrayOf("--keystore-alias", "k", "--key-pass-env", "KEY_PASS"), mapOf("KS_PASS" to "hunter2", "KEY_PASS" to "keysecret")) { cfg ->
            seenPassword = String(cfg.password)
            seenAlias = cfg.alias
            seenKeyPass = cfg.keyPassword?.let { String(it) }
            site.androidmod.patch.ApkSigner { unsigned, signed -> unsigned.copyTo(signed, overwrite = true) }
        }
        assertEquals(0, code)
        assertEquals("hunter2", seenPassword)
        assertEquals("k", seenAlias)
        assertEquals("keysecret", seenKeyPass)
        assertTrue(out.isFile)
    }
}
