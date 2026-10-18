package site.androidmod.patch

import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** A generated signing key and its self-signed certificate. */
class TestKey(val privateKey: PrivateKey, val cert: X509Certificate)

/** Test signing keys, generated with the JDK's keytool (no BouncyCastle needed). */
object TestKeys {
    fun generate(cn: String = "Test Signer"): TestKey {
        val dir = Files.createTempDirectory("testkey").toFile()
        try {
            val ks = File(dir, "k.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val p = ProcessBuilder(
                keytool, "-genkeypair", "-alias", "k", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-dname", "CN=$cn", "-storetype", "PKCS12",
                "-keystore", ks.path, "-storepass", "password", "-keypass", "password",
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "keytool failed: $out" }
            val store = KeyStore.getInstance("PKCS12")
            ks.inputStream().use { store.load(it, "password".toCharArray()) }
            return TestKey(
                store.getKey("k", "password".toCharArray()) as PrivateKey,
                store.getCertificate("k") as X509Certificate,
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** Signs in-process with apksig under [key]: v2 and v3, minSdk 33, no v1 (what `PatchEngineTest.signedOfficial` does). */
@Suppress("DEPRECATION")
fun apksigSigner(key: TestKey): ApkSigner = ApkSigner { unsigned, signed ->
    com.android.apksig.ApkSigner.Builder(
        listOf(com.android.apksig.ApkSigner.SignerConfig.Builder("t", key.privateKey, listOf(key.cert)).build()),
    ).setInputApk(unsigned).setOutputApk(signed).setMinSdkVersion(33)
        .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).build().sign()
}
