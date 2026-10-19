package site.androidmod.store.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkSetTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun apk(name: String, bytes: Int) = folder.newFile(name).also { it.writeBytes(ByteArray(bytes) { 7 }) }

    @Test
    fun apk_set_keeps_order_names_and_sizes() {
        val base = apk("base.apk", 10)
        val config = apk("split_config.en.apk", 3)
        val contents = apkSetContents(listOf(base, config))

        assertEquals(listOf("base.apk", "split_config.en.apk"), contents.parts.map { it.sessionName })
        assertEquals(listOf(10L, 3L), contents.parts.map { it.size })
        assertTrue(contents.isBundle)
        assertNull(contents.packageName)
        assertSame(base, contents.parts.first().file)
        assertEquals(10, BundleReader.openPart(base, contents.parts[0]).use { it.readBytes().size })
        assertEquals(3, BundleReader.openPart(base, contents.parts[1]).use { it.readBytes().size })
        assertFalse(apkSetContents(listOf(base)).isBundle)
    }

    @Test
    fun apk_set_refuses_empty_and_duplicate_names() {
        val a = apk("a.apk", 1)
        val sub = folder.newFolder("other")
        val sameName = java.io.File(sub, "a.apk").also { it.writeBytes(byteArrayOf(1)) }

        for (bad in listOf(emptyList(), listOf(a, sameName))) {
            try {
                apkSetContents(bad)
                fail("expected a BundleException")
            } catch (expected: BundleException) {
                assertNotNull(expected.message)
            }
        }
    }

    @Test
    fun apk_set_refuses_names_that_are_unsafe_for_a_pm_command() {
        for (name in listOf("a b.apk", "x;rm.apk", "-r.apk", ".hidden.apk")) {
            try {
                apkSetContents(listOf(apk(name, 1)))
                fail("expected a BundleException for $name")
            } catch (expected: BundleException) {
            }
        }
    }
}
