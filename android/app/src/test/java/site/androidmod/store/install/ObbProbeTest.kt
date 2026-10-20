package site.androidmod.store.install

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

class ObbProbeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun obbDir(root: File, pkg: String) = File(root, "Android/obb/$pkg")

    @Test
    fun the_folder_rule_is_true_false_or_unknown() {
        val root = folder.newFolder()
        // Most apps have no obb folder at all: unknown, which doesn't block.
        assertNull(obbFolderHasFiles(root, PKG))

        obbDir(root, PKG).mkdirs()
        assertEquals(false, obbFolderHasFiles(root, PKG))

        File(obbDir(root, PKG), "main.7.$PKG.obb").writeBytes(ByteArray(4))
        assertEquals(true, obbFolderHasFiles(root, PKG))
    }

    @Test
    fun a_folder_that_cannot_be_listed_is_unknown() {
        val root = folder.newFolder()
        obbDir(root, PKG).parentFile!!.mkdirs()
        obbDir(root, PKG).writeText("a file where the folder should be")
        assertNull(obbFolderHasFiles(root, PKG))
    }

    @Test
    fun only_a_package_name_is_looked_up() {
        val root = folder.newFolder()
        File(root, "Android/obb").mkdirs()
        // What a crafted argument could reach outside Android/obb/<package>.
        File(root, "Android/data/com.other").mkdirs()
        File(root, "Android/data/com.other/secret").writeText("x")
        File(root, "Android/obb/com.other").mkdirs()
        File(root, "Android/obb/com.other/main.1.obb").writeText("x")

        val bad = listOf(
            "", ".", "..", "../data/com.other", "com.other/..", "com.other/main.1.obb", "/etc", "a/b",
            "com..other", ".com.other", "com.other.", "com other", "com.other\u0000", "çom.other", "a".repeat(256),
        )
        for (name in bad) {
            assertFalse(name, isPackageName(name))
            assertNull(name, obbFolderHasFiles(root, name))
        }
        for (name in listOf("com.other", "com.droidhen.defender2", "a", "A_b.C1.d_2", "a".repeat(255))) {
            assertTrue(name, isPackageName(name))
        }
        assertEquals(true, obbFolderHasFiles(root, "com.other"))
    }

    @Test
    fun answers_travel_as_codes() {
        for (answer in listOf(true, false, null)) {
            assertEquals("$answer", answer, ObbProbeProtocol.answerOf(ObbProbeProtocol.codeOf(answer)))
        }
        assertNull("a code it doesn't know is unknown", ObbProbeProtocol.answerOf(42))
        assertEquals("site.androidmod.store.obbprobe", ObbProbeProtocol.authority("site.androidmod.store"))
    }

    @Test
    fun the_probe_answers_its_method_with_the_folder_rule() {
        val root = folder.newFolder()
        obbDir(root, PKG).mkdirs()
        File(obbDir(root, PKG), "main.7.$PKG.obb").writeBytes(ByteArray(4))
        val method = ObbProbeProtocol.METHOD

        assertEquals(ObbProbeProtocol.codeOf(true), probeAnswer(method, PKG, root))
        assertEquals(ObbProbeProtocol.codeOf(null), probeAnswer(method, "com.empty", root))
        assertEquals("no package", ObbProbeProtocol.codeOf(null), probeAnswer(method, null, root))
        assertEquals("not a package name", ObbProbeProtocol.codeOf(null), probeAnswer(method, "../obb/$PKG", root))
        assertNull("another method", probeAnswer("query", PKG, root))
    }

    @Test
    fun the_client_asks_once_more_when_the_probe_is_gone() {
        // Stands in for RemoteException (DeadObjectException included): the probe's process died, or ended itself.
        val gone = IllegalStateException("the probe's process died")

        fun ask(vararg replies: () -> Int?): Pair<Boolean?, Int> {
            var calls = 0
            val answer = askProbe(retryable = { it === gone }) { replies[calls++]() }
            return answer to calls
        }

        assertEquals(true to 1, ask({ ObbProbeProtocol.codeOf(true) }))
        assertEquals("unknown is an answer, not a miss", null to 1, ask({ ObbProbeProtocol.codeOf(null) }))
        assertEquals("not reached: once more", false to 2, ask({ null }, { ObbProbeProtocol.codeOf(false) }))
        assertEquals("died: once more", true to 2, ask({ throw gone }, { ObbProbeProtocol.codeOf(true) }))
        assertEquals("twice: unknown", null to 2, ask({ throw gone }, { throw gone }))
        assertEquals(null to 2, ask({ null }, { null }))
        assertEquals("any other failure: unknown at once", null to 1, ask({ throw SecurityException("denied") }))
    }

    @Test
    fun the_probe_is_private_and_runs_in_its_own_process() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val providers = manifest.getElementsByTagName("provider")
        val probe = (0 until providers.length).map { providers.item(it) as Element }
            .singleOrNull { it.getAttributeNS(ANDROID, "name") == ".install.ObbProbeProvider" }
        assertNotNull(probe)
        assertEquals("false", probe!!.getAttributeNS(ANDROID, "exported"))
        assertEquals(":obbprobe", probe.getAttributeNS(ANDROID, "process"))
        assertEquals("\${applicationId}.obbprobe", probe.getAttributeNS(ANDROID, "authorities"))
    }

    private companion object {
        const val PKG = "com.droidhen.defender2"
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
