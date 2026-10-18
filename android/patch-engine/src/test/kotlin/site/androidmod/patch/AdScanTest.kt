package site.androidmod.patch

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val catalogJson = Json { ignoreUnknownKeys = true }

/** The real catalog plan 1 exports, as the generic bundle carries it. */
fun genericCatalog(): AdCatalog = catalogJson.decodeFromString(
    AdCatalog.serializer(),
    AdScanTest::class.java.getResource("/generic/catalog.json")!!.readText(),
)

class AdScanTest {
    @get:Rule val tmp = TemporaryFolder()
    private val catalog = genericCatalog()

    /** One dex whose classes each hold the literals at the given string indices, one method per class. */
    private fun dex(strings: List<String>, vararg classes: Pair<String, List<Int>>) = DexFile(TinyDex.build(
        strings,
        classes.map { (d, idx) -> TinyClass(d, listOf(TinyMethod(idx.flatMap { TinyDex.constString(it) }))) },
    ))

    private fun scan(vararg dexes: DexFile) = AdScan.scanDex(dexes.toList(), catalog)
    private fun harvested(result: AdScanResult) = result.artifacts.getValue("AD_HOSTS_HARVESTED")

    @Test fun findsSdksAndHarvestsTheirHosts() {
        val strings = listOf("https://ms.applvn.com/x", "https://edge.adnet-test.io/v1", "https://other.adnet-test.io/")
        val d = DexFile(TinyDex.build(strings, listOf(
            TinyClass("Lcom/applovin/a/B;", listOf(TinyMethod(TinyDex.constString(0) + TinyDex.constStringJumbo(1)))),
            TinyClass("Lcom/example/app/C;", listOf(TinyMethod(TinyDex.constString(2)))),
        )))
        val a = scan(d).artifacts
        assertEquals("AppLovin", a["AD_SDKS_PRESENT"])
        assertEquals("edge.adnet-test.io", a["AD_HOSTS_HARVESTED"])
        assertEquals("true", a["AD_CALLER_BLOCK"])
    }

    @Test fun quotedJsonLiteralIsHarvested() {
        val r = scan(dex(listOf("{\"u\":\"https://ads.x.io\"}"), "Lcom/applovin/J;" to listOf(0)))
        assertTrue(harvested(r), "ads.x.io" in harvested(r).split(','))
    }

    @Test fun escapesDecideLikeSmali() {
        // A bare host is harvested; the escaped `\n` makes no hostname; `io.` reads as a package. The last two
        // are harvested only because smali escapes what follows the host (a newline mid-literal, a non-ASCII char).
        val strings = listOf("tracker.adnet-test.io", "ads.example-test.com\n", "io.adnet-test.com",
            "https://nl.adnet-test.io\nnext", "https://u.adnet-test.io\u00e9")
        assertEquals(
            "nl.adnet-test.io,tracker.adnet-test.io,u.adnet-test.io",
            harvested(scan(dex(strings, "Lcom/applovin/E;" to listOf(0, 1, 2, 3, 4)))),
        )
    }

    @Test fun presentFollowsCatalogOrder() {
        val r = scan(dex(emptyList(), "Lcom/unity3d/ads/U;" to emptyList(), "Lcom/applovin/A;" to emptyList()))
        assertEquals("AppLovin, Unity Ads", r.artifacts["AD_SDKS_PRESENT"])
    }

    @Test fun harvestedHostsAreDedupedAndSorted() {
        val strings = listOf("https://b.adnet-test.io/", "https://a.adnet-test.io/x", "https://B.ADNET-TEST.IO/y", "wss://a.adnet-test.io")
        val r = scan(
            dex(strings, "Lcom/applovin/A;" to listOf(0, 1, 2)),
            dex(strings, "Lcom/unity3d/ads/U;" to listOf(3, 0)),
        )
        assertEquals("a.adnet-test.io,b.adnet-test.io", harvested(r))
    }

    @Test fun protectedAndIpHostsNotHarvested() {
        val r = scan(dex(listOf("https://cdn.example.com/x", "http://10.0.0.1/ad"), "Lcom/applovin/P;" to listOf(0, 1)))
        assertEquals("", harvested(r))
    }

    @Test fun noAdsStillGivesEveryKey() {
        val a = scan(dex(listOf("https://edge.adnet-test.io/"), "Lcom/example/app/C;" to listOf(0))).artifacts
        assertEquals(
            setOf("AD_SDKS_PRESENT", "AD_SDK_PACKAGES", "AD_APP_FRAME_PACKAGES", "AD_HOST_SUFFIXES",
                "AD_HOSTS_HARVESTED", "AD_PROTECTED_SUFFIXES", "AD_URL_RULES", "AD_CALLER_BLOCK"),
            a.keys,
        )
        assertEquals("", a["AD_SDKS_PRESENT"])
        assertEquals("", a["AD_HOSTS_HARVESTED"])
        assertTrue(a.getValue("AD_HOST_SUFFIXES").isNotEmpty())
    }

    @Test fun flagsPairipAndEarlierPatch() {
        fun flags(vararg classes: String) = scan(dex(emptyList(), *classes.map { it to emptyList<Int>() }.toTypedArray()))
            .let { it.pairip to it.patchedBefore }
        assertEquals(false to false, flags("Lcom/example/A;", "Lcom/pairipx/A;", "Lcom/paywall/Other;"))
        assertEquals(true to false, flags("Lcom/pairip/VMRunner;"))
        assertEquals(false to true, flags("Lcom/smali_generator/InitProviderGeneric;"))
        assertEquals(false to true, flags("Lcom/paywall/InitProviderPaywall;"))
    }

    @Test fun scanReadsTopLevelDexOfEveryApkAndSkipsOnlyInBase() {
        val applovin = TinyDex.build(emptyList(), listOf(TinyClass("Lcom/applovin/A;")))
        val unity = TinyDex.build(emptyList(), listOf(TinyClass("Lcom/unity3d/ads/U;")))
        val vungle = TinyDex.build(emptyList(), listOf(TinyClass("Lcom/vungle/V;")))
        val inmobi = TinyDex.build(emptyList(), listOf(TinyClass("Lcom/inmobi/I;")))
        val base = zip(tmp.newFile("base.apk"), listOf(
            "classes.dex" to applovin, "classes2.dex" to unity, "assets/audience_network.dex" to vungle,
        ))
        val split = zip(tmp.newFile("split.apk"), listOf("classes2.dex" to inmobi))
        val present = { apks: List<File>, skip: Set<String> -> AdScan.scan(apks, catalog, skip).artifacts["AD_SDKS_PRESENT"] }
        assertEquals("AppLovin, Unity Ads, InMobi", present(listOf(base, split), emptySet()))
        assertEquals("AppLovin, InMobi", present(listOf(base, split), setOf("classes2.dex")))
    }
}
