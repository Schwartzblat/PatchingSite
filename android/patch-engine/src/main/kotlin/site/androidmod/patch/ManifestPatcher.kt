package site.androidmod.patch

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.ByteArrayInputStream

/** What the store needs to know about an app from its binary manifest alone. */
data class ManifestFacts(
    val packageName: String,
    val versionCode: Long?,
    val versionName: String?,
    val split: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val sharedUserId: String?,
    /** `android:name` of every activity, alias, service, receiver and provider, as written. */
    val componentNames: List<String>,
    /** `android:name` of `<application>` (its Application class), as written. */
    val applicationName: String? = null,
)

/** Merges the bundle's `manifest-additions.xml` (binary AXML) into the app's binary manifest. */
object ManifestPatcher {
    private const val ATTR_NAME = 0x01010003
    private const val ATTR_AUTHORITIES = 0x01010018
    private const val ATTR_SHARED_USER_ID = 0x0101000b
    private val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")

    fun packageName(manifest: ByteArray): String =
        load(manifest).packageName ?: throw PatchException.BadApk("Manifest has no package name")

    fun versionCode(manifest: ByteArray): Long? = load(manifest).versionCode?.toLong()

    fun facts(manifest: ByteArray): ManifestFacts {
        val m = load(manifest)
        val root = m.manifestElement
        val components = m.applicationElement?.let { app ->
            children(app).filter { it.name in COMPONENT_TAGS }.mapNotNull { attrValue(it, ATTR_NAME) }
        }.orEmpty()
        return ManifestFacts(
            packageName = m.packageName ?: throw PatchException.BadApk("Manifest has no package name"),
            versionCode = m.versionCode?.toLong(),
            versionName = m.versionName,
            split = m.split,
            minSdk = m.minSdkVersion,
            targetSdk = m.targetSdkVersion,
            sharedUserId = root?.let { attrValue(it, ATTR_SHARED_USER_ID) },
            componentNames = components,
            applicationName = m.applicationElement?.let { attrValue(it, ATTR_NAME) },
        )
    }

    /** Every `;`-separated `<provider>` authority in [manifest], as written. */
    fun providerAuthorities(manifest: ByteArray): List<String> {
        val app = load(manifest).applicationElement ?: return emptyList()
        return children(app).filter { it.name == "provider" }
            .mapNotNull { attrValue(it, ATTR_AUTHORITIES) }.flatMap { it.split(';') }
    }

    /**
     * Rewrites every `<provider>` authority (each `;`-separated one) that starts with [from] to start with
     * [to] instead; the rest of the manifest and other authorities are kept.
     */
    fun rewriteAuthorities(additions: ByteArray, from: String, to: String): ByteArray {
        val src = load(additions)
        try {
            // The bundle's manifest can't be refreshed in place (its resource-id map has unnamed
            // entries ARSCLib refuses to re-sort), so copy it into a fresh block and edit the copy.
            val out = AndroidManifestBlock.empty()
            out.packageName = src.packageName // creates the <manifest> element
            out.manifestElement.merge(src.manifestElement)
            val app = out.applicationElement
            if (app != null) {
                for (provider in children(app).filter { it.name == "provider" }) {
                    val attr = provider.attributes.asSequence().firstOrNull { it.nameId == ATTR_AUTHORITIES } ?: continue
                    val value = attr.valueAsString ?: continue
                    attr.setValueAsString(
                        value.split(';').joinToString(";") { if (it.startsWith(from)) to + it.removePrefix(from) else it },
                    )
                }
            }
            out.refreshFull()
            return out.bytes
        } catch (e: Exception) {
            throw PatchException.BadApk("Manifest rewrite failed: ${e.message}")
        }
    }

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
