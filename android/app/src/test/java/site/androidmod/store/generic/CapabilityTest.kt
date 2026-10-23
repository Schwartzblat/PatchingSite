package site.androidmod.store.generic

import org.junit.Assert.assertEquals
import org.junit.Test
import site.androidmod.patch.Capability

/** The store's reading of the capability wire names: Capability.of, case aside, unknown names ignored. */
class CapabilityTest {

    @Test
    fun parsesKnownNamesOnly() {
        assertEquals(setOf(Capability.ADS, Capability.IAP), capabilitiesOf(listOf("ads", "surprise", "iap")))
        assertEquals(setOf(Capability.ADS, Capability.IAP), capabilitiesOf(listOf("ADS", "Iap", " ads ")))
        assertEquals(emptySet<Capability>(), capabilitiesOf(listOf("surprise")))
        assertEquals(emptySet<Capability>(), capabilitiesOf(emptyList()))
        // Wire order, never the caller's.
        assertEquals(listOf(Capability.ADS, Capability.IAP), capabilitiesOf(listOf("iap", "ads")).toList())
    }
}
