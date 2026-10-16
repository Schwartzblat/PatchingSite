package site.androidmod.patch

import com.android.apksig.ApkVerifier
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class FastSignerTest {
    private lateinit var dir: File
    private lateinit var binary: File

    @Before
    fun setUp() {
        val path = System.getProperty("patch.fastsigner")
        assumeTrue("patch.fastsigner absent", path != null && File(path).canExecute())
        binary = File(path!!)
        dir = Files.createTempDirectory("fastsigner").toFile()
    }

    @After
    fun tearDown() {
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun keytool(vararg args: String) {
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(listOf(keytool) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "keytool failed: $out" }
    }

    /** PKCS#12 store with one EC P-256 key per alias, all under [pass]. */
    private fun keystore(pass: String, vararg aliases: String): File {
        val ks = File(dir, "ks-${aliases.joinToString("-")}.p12")
        for (alias in aliases) {
            keytool(
                "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
                "-validity", "3650", "-dname", "CN=$alias", "-storetype", "PKCS12",
                "-keystore", ks.path, "-storepass", pass, "-keypass", pass,
            )
        }
        return ks
    }

    private fun cert(ks: File, pass: String, alias: String): X509Certificate {
        val store = KeyStore.getInstance("PKCS12")
        ks.inputStream().use { store.load(it, pass.toCharArray()) }
        return store.getCertificate(alias) as X509Certificate
    }

    private fun unsigned(): File = SyntheticApks.create(dir).let {
        File(dir, "unsigned.apk").also { out -> ApkBuilder.build(it.app, it.inputs, out) }
    }

    private fun verify(apk: File, expected: X509Certificate) {
        val r = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(30).build().verify()
        assertTrue("verify errors: ${r.errors}", r.isVerified)
        assertTrue(r.isVerifiedUsingV3Scheme)
        // At API 30 apksig skips v2 when v3 verifies; check v2 is also present for API 24-27.
        val legacy = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(24).build().verify()
        assertTrue("legacy errors: ${legacy.errors}", legacy.isVerified)
        assertTrue(legacy.isVerifiedUsingV2Scheme)
        assertEquals(listOf(expected), r.signerCertificates)
    }

    @Test
    fun signsAndVerifies() {
        val ks = keystore("secret", "k")
        val signed = File(dir, "signed.apk")
        FastSigner(binary, ks, "secret".toCharArray()).sign(unsigned(), signed)
        verify(signed, cert(ks, "secret", "k"))
    }

    @Test
    fun aliasSelectsTheKey() {
        val ks = keystore("secret", "first", "second")
        val signed = File(dir, "signed.apk")
        FastSigner(binary, ks, "secret".toCharArray(), alias = "second").sign(unsigned(), signed)
        verify(signed, cert(ks, "secret", "second"))
    }

    // keytool refuses distinct key and store passwords for PKCS#12 (it ignores -keypass), so
    // this exercises the --key-pass env: path with an equal password; the env route is still
    // proven to be taken because the binary must read the variable to succeed.
    @Test
    fun keyPasswordViaEnvSigns() {
        val ks = keystore("secret", "k")
        val signed = File(dir, "signed.apk")
        FastSigner(binary, ks, "secret".toCharArray(), alias = "k", keyPassword = "secret".toCharArray())
            .sign(unsigned(), signed)
        verify(signed, cert(ks, "secret", "k"))
    }

    @Test
    fun wrongKeyPasswordFails() {
        val ks = keystore("secret", "k")
        val signed = File(dir, "signed.apk")
        try {
            FastSigner(binary, ks, "secret".toCharArray(), keyPassword = "nope".toCharArray())
                .sign(unsigned(), signed)
            fail("expected SignFailed")
        } catch (e: PatchException.SignFailed) {
            assertFalse(signed.exists())
        }
    }

    @Test
    fun wrongPasswordFailsCleanly() {
        val ks = keystore("secret", "k")
        val signed = File(dir, "signed.apk")
        try {
            FastSigner(binary, ks, "wrong".toCharArray()).sign(unsigned(), signed)
            fail("expected SignFailed")
        } catch (e: PatchException.SignFailed) {
            assertTrue("message: ${e.message}", e.message!!.length > "fastsigner exited 1: ".length)
            assertFalse(signed.exists())
        }
    }

    @Test
    fun missingBinaryFails() {
        val ks = keystore("secret", "k")
        val signed = File(dir, "signed.apk")
        try {
            FastSigner(File(dir, "no-such-binary"), ks, "secret".toCharArray()).sign(unsigned(), signed)
            fail("expected SignFailed")
        } catch (e: PatchException.SignFailed) {
            assertFalse(signed.exists())
        }
    }
}
