package site.androidmod.patch

import java.io.File
import java.io.IOException
import org.apache.commons.compress.archivers.zip.ZipFile

/**
 * What the ad scan found: the `AD_*` artifactory entries for the module's AdRules, whether the app is
 * pairip-protected, and whether it already carries a patcher's providers (an earlier patch).
 */
data class AdScanResult(val artifacts: Map<String, String>, val pairip: Boolean, val patchedBefore: Boolean)

/**
 * A port of genericPatcher's `ad_inventory.py` (AdInventoryFinder with harvesting and the caller block on, and
 * no extra block or allow hosts), run on dex instead of apktool's smali, so it gives the desktop's artifacts.
 *
 * The desktop walks each SDK's package directory in the smali tree and reads the `const-string` lines of each
 * file. Here a class is in that directory when its descriptor starts with `L` + the package as a path + `/`,
 * and each literal is put back into its smali form with [SmaliText.escape] before the same regexes run. That
 * text is printable ASCII with no line breaks, so Java's and Python's regex semantics agree on it.
 */
object AdScan {
    // The Python regexes, unchanged. Python's re.match is matchesAt(_, 0) here; finditer is findAll.
    private val URL_HOST_RE = Regex("""\b(?:https?|wss?)://([A-Za-z0-9.-]+)(?=[:/?#\\]|$)""")
    private val HOSTNAME_RE = Regex("""^(?=.{4,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24}$""")
    private val IPV4_RE = Regex("""^\d+(?:\.\d+){3}$""")

    /** Every top-level classesN.dex of every APK, except names in [skipInBase] (base only). */
    fun scan(apks: List<File>, catalog: AdCatalog, skipInBase: Set<String> = emptySet()): AdScanResult {
        val scan = Scan(catalog)
        apks.forEachIndexed { n, apk ->
            try {
                ZipFile.builder().setFile(apk).get().use { zip ->
                    for (entry in zip.entries) {
                        if (DexHash.dexIndex(entry.name) == null || (n == 0 && entry.name in skipInBase)) continue
                        scan.add(DexFile(zip.getInputStream(entry).use { it.readBytes() }))
                    }
                }
            } catch (e: IOException) {
                throw PatchException.BadApk("cannot read ${apk.name}: ${e.message}")
            }
        }
        return scan.result()
    }

    fun scanDex(dexes: List<DexFile>, catalog: AdCatalog): AdScanResult =
        Scan(catalog).apply { dexes.forEach(::add) }.result()

    private class Scan(private val catalog: AdCatalog) {
        /** Per catalog SDK, the descriptor prefix of each package (`com.applovin.` gives `Lcom/applovin/`). */
        private val prefixes = catalog.sdks.map { sdk -> sdk.packages.map { "L" + it.trimEnd('.').replace('.', '/') + "/" } }
        private val classes = IntArray(catalog.sdks.size)
        private val harvested = HashSet<String>()
        private val blockSuffixes = catalog.sdks.flatMap { it.hosts }
        private val protected = catalog.protectedHosts
        private val bareTlds = catalog.harvestBareTlds.toHashSet()
        private val packageRoots = catalog.packageRoots.toHashSet()
        private var pairip = false
        private var patchedBefore = false

        fun add(dex: DexFile) {
            dex.classes.forEachIndexed { i, descriptor ->
                if (descriptor.startsWith("Lcom/pairip/")) pairip = true
                if (descriptor.startsWith("Lcom/smali_generator/InitProvider") ||
                    descriptor.startsWith("Lcom/paywall/InitProviderPaywall")
                ) patchedBefore = true
                var inSdk = false
                prefixes.forEachIndexed { s, ps ->
                    if (ps.any { descriptor.startsWith(it) }) { classes[s]++; inSdk = true }
                }
                // The harvest is a set of hosts, so a class under several SDKs is read once.
                if (inSdk) for (literal in dex.constStrings(i)) harvest(SmaliText.escape(literal))
            }
        }

        private fun harvest(literal: String) {
            for (raw in hostsInLiteral(literal)) {
                val host = cleanHost(raw) ?: continue
                if (matchesSuffix(host, blockSuffixes) || matchesSuffix(host, protected)) continue
                harvested.add(host)
            }
        }

        private fun hostsInLiteral(literal: String): List<String> {
            val out = URL_HOST_RE.findAll(literal).map { it.groupValues[1] }.toMutableList()
            if ('/' !in literal && ' ' !in literal && literal.count { it == '.' } >= 1) {
                val bare = literal.lowercase()
                if (HOSTNAME_RE.matchesAt(bare, 0) && bare.substringAfterLast('.') in bareTlds &&
                    bare.substringBefore('.') !in packageRoots
                ) out.add(bare)
            }
            return out
        }

        private fun cleanHost(raw: String): String? {
            val host = raw.lowercase().trim('.')
            if (!HOSTNAME_RE.matchesAt(host, 0) || IPV4_RE.matchesAt(host, 0)) return null
            return host
        }

        private fun matchesSuffix(host: String, suffixes: List<String>) =
            suffixes.any { host == it || host.endsWith(".$it") }

        fun result(): AdScanResult {
            val present = catalog.sdks.filterIndexed { s, _ -> classes[s] > 0 }.map { it.name }
            val artifacts = linkedMapOf(
                "AD_SDKS_PRESENT" to present.joinToString(", "),
                "AD_SDK_PACKAGES" to catalog.sdks.flatMap { it.packages }.joinToString(","),
                "AD_APP_FRAME_PACKAGES" to catalog.appFramePackages.joinToString(","),
                "AD_HOST_SUFFIXES" to blockSuffixes.joinToString(","),
                "AD_HOSTS_HARVESTED" to harvested.sorted().joinToString(","),
                "AD_PROTECTED_SUFFIXES" to protected.joinToString(","),
                "AD_URL_RULES" to catalog.urlRules.joinToString(",") { (host, path) -> host + path },
                "AD_CALLER_BLOCK" to "true",
            )
            return AdScanResult(artifacts, pairip, patchedBefore)
        }
    }
}
