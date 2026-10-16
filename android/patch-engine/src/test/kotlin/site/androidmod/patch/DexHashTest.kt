package site.androidmod.patch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class DexHashTest {
    private val dex = listOf("classes10.dex" to "dex\n035\u0000ten", "classes.dex" to "dex\n035\u0000one",
        "assets/classes3.dex" to "not code", "classes.dex.bin" to "not code", "classes2.dex" to "dex\n035\u0000two")

    private fun apk(entries: List<Pair<String, String>>, stored: Boolean = false): File =
        zip(File.createTempFile("dexhash", ".apk").also { it.deleteOnExit() },
            entries.map { it.first to it.second.toByteArray() }, stored)

    @Test fun matchesSharedFixture() =
        assertEquals("5241b8649f3b1756ab0a32e13a29f534e9cd5298b004cf714d6e9a8f40a5e8fe", DexHash.dexSha256(apk(dex)))
    @Test fun compressionDoesNotMatter() = assertEquals(DexHash.dexSha256(apk(dex, stored = true)), DexHash.dexSha256(apk(dex)))
    @Test fun nonAsciiDigitsIgnored() = assertEquals(DexHash.dexSha256(apk(dex)), DexHash.dexSha256(apk(dex + ("classes٢.dex" to "x"))))
    @Test(expected = PatchException.BadApk::class) fun noDexIsAnError() { DexHash.dexSha256(apk(listOf("AndroidManifest.xml" to "x"))) }
    @Test(expected = PatchException.BadApk::class) fun duplicateEntryIsAnError() { DexHash.dexSha256(apk(dex + ("classes.dex" to "evil"))) }
    @Test fun realWhatsApp() {
        val wa = fixtureFile("com.whatsapp-2.26.40.72.apk"); assumeTrue(wa != null)
        assertEquals("d678187db51d144835a8b7609f365fe27db705cf010ec4734d93ae9d55efe8c8", DexHash.dexSha256(wa!!))
        assertEquals("4b92cf77d25492dcb381534341fb50b01d59418e408ad4c2bdffb18d2eeb807f", DexHash.apkSha256(wa))
    }
}
