package site.androidmod.patch

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.ByteArrayInputStream

/** Merges the bundle's `manifest-additions.xml` (binary AXML) into the app's binary manifest. */
object ManifestPatcher {
    private const val ATTR_NAME = 0x01010003
    private const val ATTR_AUTHORITIES = 0x01010018

    fun packageName(manifest: ByteArray): String =
        load(manifest).packageName ?: throw PatchException.BadApk("Manifest has no package name")

    fun versionCode(manifest: ByteArray): Long? = load(manifest).versionCode?.toLong()

    fun merge(appManifest: ByteArray, additions: ByteArray): ByteArray {
        val app = load(appManifest)
        val add = load(additions)
        val appEl = app.applicationElement ?: throw PatchException.BadApk("App manifest has no <application>")
        val root = app.manifestElement ?: throw PatchException.BadApk("App manifest has no root element")
        try {
            val addApp = add.applicationElement
            val addRoot = add.manifestElement
            for (child in children(addRoot)) {
                if (child === addApp || isDeclared(root, child)) continue
                root.newElementAt(indexOf(root, appEl), child.name).merge(child)
            }
            if (addApp != null) {
                for (child in children(addApp)) {
                    if (isDeclared(appEl, child)) continue
                    appEl.newElement(child.name).merge(child)
                }
            }
            app.refreshFull()
            return app.bytes
        } catch (e: PatchException) {
            throw e
        } catch (e: Exception) {
            throw PatchException.BadApk("Manifest merge failed: ${e.message}")
        }
    }

    private fun load(bytes: ByteArray): AndroidManifestBlock = try {
        AndroidManifestBlock.load(ByteArrayInputStream(bytes))
    } catch (e: Exception) {
        throw PatchException.BadApk("Not a binary Android manifest: ${e.message}")
    }

    private fun children(el: ResXmlElement): List<ResXmlElement> =
        el.elements.asSequence().map { it as ResXmlElement }.toList()

    private fun indexOf(parent: ResXmlElement, child: ResXmlElement): Int =
        children(parent).indexOfFirst { it === child }

    private fun attrValue(el: ResXmlElement, id: Int): String? =
        el.attributes.asSequence().firstOrNull { it.nameId == id }?.valueAsString

    private fun isDeclared(parent: ResXmlElement, candidate: ResXmlElement): Boolean {
        val name = attrValue(candidate, ATTR_NAME)
        val auth = if (candidate.name == "provider") attrValue(candidate, ATTR_AUTHORITIES) else null
        return children(parent).any { e ->
            e.name == candidate.name && (
                (name != null && attrValue(e, ATTR_NAME) == name) ||
                    (auth != null && attrValue(e, ATTR_AUTHORITIES) == auth)
                )
        }
    }
}
