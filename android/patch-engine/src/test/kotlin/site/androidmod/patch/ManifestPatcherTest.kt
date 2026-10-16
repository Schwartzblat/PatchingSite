package site.androidmod.patch

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ManifestPatcherTest {
    private val NAME = 0x01010003
    private val AUTH = 0x01010018

    private fun ResXmlElement.kids(tag: String): List<ResXmlElement> =
        elements.asSequence().map { it as ResXmlElement }.filter { it.name == tag }.toList()

    private fun ResXmlElement.attr(name: String): String? =
        attributes.asSequence().firstOrNull { it.name == name }?.valueAsString

    private fun load(bytes: ByteArray) = AndroidManifestBlock.load(ByteArrayInputStream(bytes))

    private fun manifest(pkg: String, build: AndroidManifestBlock.(ResXmlElement) -> Unit): ByteArray {
        val am = AndroidManifestBlock.empty()
        am.packageName = pkg
        am.build(am.getOrCreateApplicationElement())
        am.refreshFull()
        return am.bytes
    }

    private fun AndroidManifestBlock.perm(name: String) {
        manifestElement.newElement("uses-permission").getOrCreateAndroidAttribute("name", NAME).setValueAsString(name)
    }

    private fun ResXmlElement.provider(name: String, auth: String) {
        val p = newElement("provider")
        p.getOrCreateAndroidAttribute("name", NAME).setValueAsString(name)
        p.getOrCreateAndroidAttribute("authorities", AUTH).setValueAsString(auth)
        p.getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(false)
        p.getOrCreateAndroidAttribute("initOrder", 0x0101001a).apply {
            valueType = com.reandroid.arsc.value.ValueType.DEC
            data = Int.MAX_VALUE
        }
    }

    private val additions = manifest("mod") { app ->
        perm("android.permission.INTERNET")
        app.provider("mod.Init", "mod.auth")
        app.newElement("activity").getOrCreateAndroidAttribute("name", NAME).setValueAsString("mod.Act")
    }

    @Test fun appendsComponentsAndPermissions() {
        val out = load(ManifestPatcher.merge(manifest("com.app") {}, additions))
        assertEquals("com.app", ManifestPatcher.packageName(out.bytes))
        assertEquals(listOf("android.permission.INTERNET"), out.manifestElement.kids("uses-permission").map { it.attr("name") })
        val app = out.applicationElement
        val p = app.kids("provider").single()
        assertEquals("mod.Init", p.attr("name"))
        assertEquals(false, p.attributes.asSequence().first { it.name == "exported" }.valueAsBoolean)
        assertEquals(Int.MAX_VALUE, p.attributes.asSequence().first { it.name == "initOrder" }.data)
        assertEquals(listOf("mod.Act"), app.kids("activity").map { it.attr("name") })
    }

    @Test fun skipsAlreadyDeclared() {
        val base = manifest("com.app") { app ->
            perm("android.permission.INTERNET")
            app.provider("other.Provider", "mod.auth")
        }
        val out = load(ManifestPatcher.merge(base, additions))
        assertEquals(1, out.manifestElement.kids("uses-permission").size)
        assertEquals(listOf("other.Provider"), out.applicationElement.kids("provider").map { it.attr("name") })
    }

    @Test fun realWhatsAppManifestKeepsOneInternetPermission() {
        val apk = fixtureFile("com.whatsapp-2.26.40.72.apk"); assumeTrue(apk != null)
        val bundle = fixtureFile(".patcher-work/bundle-com.whatsapp-3.0.0.zip"); assumeTrue(bundle != null)
        val keyFile = File(System.getProperty("patch.bundleKey")); assumeTrue(keyFile.isFile)
        val appManifest = ZipFile(apk!!).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
        val add = PatchBundle.open(bundle!!, publicKeyFromPem(keyFile.readText())).bytes("manifest-additions.xml")
        val merged = ManifestPatcher.merge(appManifest, add)
        assertEquals("com.whatsapp", ManifestPatcher.packageName(merged))
        val out = load(merged)
        assertEquals(1, out.manifestElement.kids("uses-permission").count { it.attr("name") == "android.permission.INTERNET" })
        val providers = out.applicationElement.kids("provider").map { it.attr("name") }
        assertTrue(providers.contains("com.smali_generator.InitProvider"))
        assertTrue(providers.contains("com.paywall.InitProviderPaywallWhatsApp"))
        val acts = out.applicationElement.kids("activity").mapNotNull { it.attr("name") }.filter { it.startsWith("com.smali_generator.ui.") }
        assertEquals(8, acts.size)
    }

    @Test(expected = PatchException.BadApk::class) fun notAManifestIsBadApk() {
        ManifestPatcher.merge(byteArrayOf(1, 2, 3), additions)
    }
}
