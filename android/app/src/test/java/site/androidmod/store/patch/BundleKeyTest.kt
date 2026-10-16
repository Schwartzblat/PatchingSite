package site.androidmod.store.patch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class BundleKeyTest {
    @Test fun publicKeyParsesAsEc() {
        assertEquals("EC", BundleKey.publicKey.algorithm)
    }

    @Test fun pemMatchesTheOperatorsPublicKeyFile() {
        val file = File(System.getProperty("user.home"), ".config/androidmod/bundle-signing.pub.pem")
        assumeTrue(file.isFile)
        assertEquals(file.readText().trim(), BundleKey.PEM.trim())
    }
}
