package site.androidmod.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IapScanTest {
    private val iap = genericCatalog().iap!!

    private val anchor = "Error generating PublicKey from encoded key: "
    private val string = "Ljava/lang/String;"

    private fun method(
        name: String,
        static: Boolean,
        params: List<String>,
        returns: String,
        code: List<Int>? = emptyList(),
    ) = TinyMethod(code, direct = static, flags = if (static) 0x8 else 0, name = name, params = params, returns = returns)

    private fun dex(strings: List<String> = emptyList(), vararg classes: TinyClass) =
        DexFile(TinyDex.build(strings, classes.toList()))

    private fun scan(vararg dexes: DexFile) = IapScan.scan(dexes.toList(), iap).artifacts

    private fun verifierKeys(name: String?, method: String?, sig: String?) = mapOf(
        "IAP_VERIFIER_CLASS_NAME" to (name ?: ""),
        "IAP_VERIFIER_METHOD_NAME" to (method ?: ""),
        "IAP_VERIFIER_METHOD_SIG" to (sig ?: ""),
    )

    @Test fun findsTheVerifierAndTheCensus() {
        val d = dex(listOf(anchor), // class order is not catalog order: the census must follow the catalog
            TinyClass("Lcom/unity/purchasing/UnityPurchasing;"),
            TinyClass("Lcom/android/billingclient/api/BillingClient;"),
            TinyClass("Lcom/droidhen/billing/Security;", listOf(
                method("verifyPurchase", static = true, params = listOf(string, string), returns = "Z", code = TinyDex.constString(0)),
            )),
        )
        assertEquals(
            mapOf("IAP_CLIENTS_PRESENT" to "Play Billing Library, Unity IAP") +
                verifierKeys("com.droidhen.billing.Security", "verifyPurchase", "($string$string)Z"),
            scan(d),
        )
        assertFalse("IapScan must not compose IAP_ENABLED", scan(d).containsKey("IAP_ENABLED"))
    }

    @Test fun threeStringVerifierIsFound() {
        val d = dex(listOf(anchor),
            TinyClass("Lcom/droidhen/billing/Security;", listOf(
                method("verifyPurchase", static = true, params = listOf(string, string, string), returns = "Z", code = TinyDex.constString(0)),
            )),
        )
        assertEquals(
            mapOf("IAP_CLIENTS_PRESENT" to "") +
                verifierKeys("com.droidhen.billing.Security", "verifyPurchase", "($string$string$string)Z"),
            scan(d),
        )
    }

    @Test fun nonStaticOrWrongShapeIsNotTheVerifier() {
        val d = dex(listOf(anchor),
            TinyClass("Lcom/example/Anchored;", listOf(
                method("verifyPurchase", static = false, params = listOf(string, string), returns = "Z", code = TinyDex.constString(0)),
                method("verifyInt", static = true, params = listOf(string, "I"), returns = "Z"),
            )),
        )
        assertEquals(mapOf("IAP_CLIENTS_PRESENT" to "") + verifierKeys(null, null, null), scan(d))
    }

    @Test fun firstCandidateWins() {
        val d = dex(listOf(anchor),
            TinyClass("Lcom/example/First;", listOf(
                method("notTheShape", static = true, params = listOf(string, "I"), returns = "Z", code = TinyDex.constString(0)),
            )),
            TinyClass("Lcom/example/Second;", listOf(
                method("verify", static = true, params = listOf(string, string), returns = "Z", code = TinyDex.constString(0)),
            )),
        )
        assertEquals(
            mapOf("IAP_CLIENTS_PRESENT" to "") + verifierKeys("com.example.Second", "verify", "($string$string)Z"),
            scan(d),
        )
    }

    @Test fun absentIapSectionDegrades() {
        val d = dex(listOf(anchor),
            TinyClass("Lcom/android/billingclient/api/BillingClient;"),
            TinyClass("Lcom/droidhen/billing/Security;", listOf(
                method("verifyPurchase", static = true, params = listOf(string, string), returns = "Z", code = TinyDex.constString(0)),
            )),
        )
        assertEquals(mapOf("IAP_CLIENTS_PRESENT" to ""), IapScan.scan(listOf(d), null).artifacts)
    }

    @Test fun noBillingAtAll() {
        val d = dex(listOf("https://example.test/asset"), TinyClass("Lcom/example/app/Main;", listOf(
            TinyMethod(TinyDex.constString(0)),
        )))
        assertEquals(mapOf("IAP_CLIENTS_PRESENT" to "") + verifierKeys(null, null, null), scan(d))
    }
}
