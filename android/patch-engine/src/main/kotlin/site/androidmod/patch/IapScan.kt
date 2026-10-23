package site.androidmod.patch

import java.io.File
import java.io.IOException
import kotlin.jvm.JvmName
import org.apache.commons.compress.archivers.zip.ZipFile

/**
 * The purchase scan's artifacts: the `IAP_*` census and sample-verifier keys, without `IAP_ENABLED`
 * (the engine composes that from the user's choice).
 */
data class IapScanResult(val artifacts: Map<String, String>)

/**
 * A port of genericPatcher's `iap_inventory.py` census and sample-verifier discovery, run on dex
 * instead of apktool's smali.
 *
 * The desktop counts the smali files under each catalog client's package directories and looks for the
 * sample verifier in every class until one yields a shaped method. Here a class belongs to a client when
 * its descriptor starts with `L` + the package as a path + `/`, and its `const-string` literals decide
 * whether it is a verifier candidate; the first candidate that declares a `static`
 * `(String, String)` or `(String, String, String)` method returning `Z` wins. Exactly one verifier is
 * emitted, and candidates with no such method are skipped with no key, like the desktop.
 *
 * When the catalog has no `iap` section (an old bundle), there is nothing to count or search: only
 * `IAP_CLIENTS_PRESENT` is emitted, empty, and no verifier keys appear.
 */
object IapScan {
    private const val STRING = "Ljava/lang/String;"
    private const val CENSUS_KEY = "IAP_CLIENTS_PRESENT"
    private const val VERIFIER_CLASS_KEY = "IAP_VERIFIER_CLASS_NAME"
    private const val VERIFIER_METHOD_KEY = "IAP_VERIFIER_METHOD_NAME"
    private const val VERIFIER_SIG_KEY = "IAP_VERIFIER_METHOD_SIG"

    fun scan(dexes: List<DexFile>, iap: IapCatalogData?): IapScanResult =
        Scan(iap).apply { dexes.forEach(::add) }.result()

    /** Every top-level classesN.dex of every APK, like [AdScan.scan]. */
    @JvmName("scanApks")
    fun scan(apks: List<File>, iap: IapCatalogData?): IapScanResult {
        val scan = Scan(iap)
        apks.forEach { apk ->
            try {
                ZipFile.builder().setFile(apk).get().use { zip ->
                    for (entry in zip.entries) {
                        if (DexHash.dexIndex(entry.name) == null) continue
                        scan.add(DexFile(zip.getInputStream(entry).use { it.readBytes() }))
                    }
                }
            } catch (e: IOException) {
                throw PatchException.BadApk("cannot read ${apk.name}: ${e.message}")
            }
        }
        return scan.result()
    }

    private class Scan(private val iap: IapCatalogData?) {
        /** Per catalog client, the descriptor prefix of each package (`com.android.billingclient` gives `Lcom/android/billingclient/`). */
        private val prefixes = iap?.clients?.map { client ->
            client.packages.map { "L" + it.trimEnd('.').replace('.', '/') + "/" }
        } ?: emptyList()
        private val classes = IntArray(prefixes.size)
        private var verifierClass: String? = null
        private var verifierMethod: DexMethodSig? = null

        fun add(dex: DexFile) {
            dex.classes.forEachIndexed { i, descriptor ->
                prefixes.forEachIndexed { c, ps -> if (ps.any { descriptor.startsWith(it) }) classes[c]++ }
                val catalog = iap ?: return@forEachIndexed
                if (verifierMethod != null) return@forEachIndexed
                val anchored = dex.constStrings(i).any { literal -> catalog.verifierAnchors.any { it in literal } }
                if (!anchored) return@forEachIndexed
                val shaped = dex.methodSigs(i).firstOrNull(::isVerifier)
                if (shaped != null) {
                    verifierClass = descriptor
                    verifierMethod = shaped
                }
            }
        }

        private fun isVerifier(sig: DexMethodSig): Boolean =
            sig.static && sig.returns == "Z" &&
                (sig.params == listOf(STRING, STRING) || sig.params == listOf(STRING, STRING, STRING))

        fun result(): IapScanResult {
            if (iap == null) return IapScanResult(mapOf(CENSUS_KEY to ""))
            val present = iap.clients.filterIndexed { c, _ -> classes[c] > 0 }.map { it.name }
            val method = verifierMethod
            return IapScanResult(linkedMapOf(
                CENSUS_KEY to present.joinToString(", "),
                VERIFIER_CLASS_KEY to (verifierClass?.let(::dotted) ?: ""),
                VERIFIER_METHOD_KEY to (method?.name ?: ""),
                VERIFIER_SIG_KEY to (method?.let { "(" + it.params.joinToString("") + ")" + it.returns } ?: ""),
            ))
        }

        /** `Lcom/droidhen/billing/Security;` reads as `com.droidhen.billing.Security`, as the desktop prints it. */
        private fun dotted(descriptor: String): String =
            if (descriptor.length >= 2 && descriptor.startsWith("L") && descriptor.endsWith(";")) {
                descriptor.substring(1, descriptor.length - 1).replace('/', '.')
            } else {
                descriptor
            }
    }
}
