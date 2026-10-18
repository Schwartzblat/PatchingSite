package site.androidmod.patch.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import com.android.apksig.ApkVerifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import site.androidmod.patch.GenericFixtures

class GenericMainTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fx = GenericFixtures(tmp)

    private class Output(val code: Int, val out: String, val err: String)

    private fun capture(args: List<String>, env: Map<String, String> = emptyMap()): Output {
        val realOut = System.out
        val realErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        try {
            val code = run(args.toTypedArray(), env)
            return Output(code, out.toString(), err.toString())
        } finally {
            System.setOut(realOut)
            System.setErr(realErr)
        }
    }

    private fun bundleArgs(bundle: File): List<String> {
        val key = tmp.newFile().also { it.writeText(fx.publicKeyPem) }
        return listOf("--bundle", bundle.path, "--bundle-key", key.path)
    }

    private fun scanArgs(apks: List<File>, bundle: File = fx.bundleFile()) =
        listOf("scan", "--base", apks[0].path) + apks.drop(1).flatMap { listOf("--split", it.path) } + bundleArgs(bundle)

    private val genericBase = listOf(
        "--base", "b.apk", "--bundle", "x.zip", "--bundle-key", "k.pem", "--fastsigner", "f",
        "--keystore", "ks.p12", "--keystore-pass-env", "KS", "--abis", "arm64-v8a", "--sdk", "36", "--out-dir", "o",
    )

    private fun without(flag: String): List<String> {
        val i = genericBase.indexOf(flag)
        return genericBase.take(i) + genericBase.drop(i + 2)
    }

    @Test fun genericUsageErrors() {
        val env = mapOf("KS" to "pw")
        fun code(args: List<String>) = capture(listOf("generic") + args, env).code
        assertEquals("missing --base", 2, code(without("--base")))
        assertEquals("--base twice", 2, code(genericBase + listOf("--base", "c.apk")))
        assertEquals("unknown flag", 2, code(genericBase + listOf("--bogus", "x")))
        assertEquals("password flag", 2, code(genericBase + listOf("--keystore-pass", "secret")))
        assertEquals("--sdk not a number", 2, code(without("--sdk") + listOf("--sdk", "x")))
        assertEquals("empty ABI entry", 2, code(without("--abis") + listOf("--abis", "arm64-v8a,")))
        assertEquals("no password in env", 2, capture(listOf("generic") + genericBase).code)
        assertEquals("scan: missing bundle", 2, capture(listOf("scan", "--base", "b.apk")).code)
        assertEquals("scan: --refresh is generic's", 2, capture(listOf("scan", "--base", "b.apk", "--refresh")).code)
        // Two --split flags parse: with real input files the run gets past usage checks and fails on the missing keystore.
        val files = listOf("b.apk", "s1.apk", "s2.apk", "x.zip", "k.pem").associateWith { tmp.newFile(it).path }
        val args = genericBase.map { files[it] ?: it } + listOf("--split", files.getValue("s1.apk"), "--split", files.getValue("s2.apk"))
        val twoSplits = capture(listOf("generic") + args, env)
        assertEquals(twoSplits.err, 1, twoSplits.code)
        assertFalse(twoSplits.err, "usage:" in twoSplits.err)
    }

    @Test fun scanPrintsArtifacts() {
        val r = capture(scanArgs(fx.app()))
        assertEquals(r.err, 0, r.code)
        val art = Json.parseToJsonElement(r.out).jsonObject
        assertEquals("AppLovin", art.getValue("AD_SDKS_PRESENT").jsonPrimitive.content)
        assertEquals(art.keys.sorted(), art.keys.toList())
        assertTrue(r.out, r.out.startsWith("{\n  \"AD_APP_FRAME_PACKAGES\""))
        assertTrue(r.out.endsWith("}\n"))
    }

    @Test fun scanRefusesAppBundle() {
        val r = capture(scanArgs(fx.app(), fx.bundleFile(pkg = GenericFixtures.PKG)))
        assertEquals(r.err, 1, r.code)
        assertTrue(r.err, "generic" in r.err)
    }

    private fun keystore(): Triple<File, String, X509Certificate> {
        val ks = File(tmp.root, "user.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "k", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
            "-dname", "CN=cli-user", "-storetype", "PKCS12", "-keystore", ks.path,
            "-storepass:env", "KS_PW", "-keypass:env", "KS_PW",
        ).redirectErrorStream(true)
        p.environment()["KS_PW"] = "cli-test-password"
        val proc = p.start()
        val text = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "keytool failed: $text" }
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "cli-test-password".toCharArray()) } }
        return Triple(ks, "cli-test-password", store.getCertificate("k") as X509Certificate)
    }

    private fun genericArgs(apks: List<File>, ks: File, out: File, extra: List<String> = emptyList()) =
        listOf("generic", "--base", apks[0].path) + apks.drop(1).flatMap { listOf("--split", it.path) } +
            bundleArgs(fx.bundleFile()) + listOf(
                "--fastsigner", System.getProperty("patch.fastsigner"), "--keystore", ks.path,
                "--keystore-pass-env", "KS_PASS", "--abis", "arm64-v8a,armeabi-v7a", "--sdk", "36",
                "--out-dir", out.path,
            ) + extra

    @Test fun genericBuildsVerifiedApks() {
        assumeTrue(File(System.getProperty("patch.fastsigner")).canExecute())
        val (ks, password, cert) = keystore()
        val apks = fx.app().let { listOf(it[0], it[1]) }
        val out = File(tmp.root, "out")
        val r = capture(genericArgs(apks, ks, out), mapOf("KS_PASS" to password))
        assertEquals(r.err, 0, r.code)
        assertEquals(listOf("base.apk", "split_config.arm64_v8a.apk"), out.list()!!.sorted())
        for (name in out.list()!!) {
            val v = ApkVerifier.Builder(File(out, name)).setMinCheckedPlatformVersion(36).build().verify()
            assertTrue("$name: ${v.errors}", v.isVerified)
            assertEquals(name, listOf(cert), v.signerCertificates)
        }
        assertTrue(r.out, r.out.contains(File(out, "base.apk").path))
        assertTrue(r.out, "com.example.app" in r.out && "AppLovin" in r.out)
        assertFalse(r.out + r.err, password in r.out + r.err)
        // Refresh from the patched copy works through the same command.
        val again = capture(genericArgs(out.listFiles()!!.sortedBy { it.name }, ks, File(tmp.root, "out2"), listOf("--refresh")), mapOf("KS_PASS" to password))
        assertEquals(again.err, 0, again.code)
    }

    @Test fun unpatchablePrintsReasons() {
        val (ks, password, _) = keystore()
        val apks = fx.app(baseExtra = listOf("lib/arm64-v8a/libpairipcore.so" to ByteArray(8)))
        val out = File(tmp.root, "out")
        val r = capture(genericArgs(apks, ks, out), mapOf("KS_PASS" to password))
        assertEquals(r.err, 1, r.code)
        assertTrue(r.err, r.err.contains("patch-cli: cannot patch: PAIRIP"))
        assertFalse(out.exists() && out.list().orEmpty().isNotEmpty())
    }
}
