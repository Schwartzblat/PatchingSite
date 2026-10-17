package site.androidmod.store.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import site.androidmod.store.install.InstalledApp

class UpdateOfferTest {

    private val user = "user-cert"
    private val shared = "shared-cert"

    private fun latest(versionCode: Long = 100, revision: Int = 2, off: List<String> = emptyList()) = LatestPatch(
        "com.whatsapp", versionCode, "2.25.1", "https://x/a.apk", "aa", "bb", "1.0", revision, off,
    )

    private fun installed(versionCode: Long = 100, signer: String? = user) =
        InstalledApp("com.whatsapp", "2.25.0", versionCode, "/data/app/base.apk", signer)

    private fun info(revision: Int, off: List<String> = emptyList()) = PatchInfo("1.0", revision, "bb", off)

    private fun offer(
        installed: InstalledApp?,
        patchInfo: PatchInfo?,
        latest: LatestPatch,
        sdkInt: Int = 33,
    ) = updateOffer(installed, patchInfo, latest, user, shared, sdkInt)

    @Test
    fun `newer version`() {
        assertEquals(
            UpdateOffer.NewVersion("2.25.1"),
            offer(installed(versionCode = 99), info(2), latest(versionCode = 100)),
        )
    }

    @Test
    fun `same version higher revision brings features back`() {
        assertEquals(
            UpdateOffer.PatchUpdate(listOf("b")),
            offer(installed(), info(1, listOf("a", "b")), latest(revision = 2, off = listOf("a"))),
        )
    }

    @Test
    fun `same version same revision is none`() {
        assertEquals(UpdateOffer.None, offer(installed(), info(2), latest(revision = 2)))
    }

    @Test
    fun `previous build needs a move`() {
        assertEquals(UpdateOffer.NeedsMove, offer(installed(signer = shared), null, latest()))
    }

    @Test
    fun `regular WhatsApp gets no offer`() {
        assertEquals(UpdateOffer.None, offer(installed(signer = "play-cert"), null, latest(versionCode = 200)))
    }

    @Test
    fun `not installed gets no offer`() {
        assertEquals(UpdateOffer.None, offer(null, null, latest()))
    }

    @Test
    fun `missing patch info counts as revision zero`() {
        assertEquals(UpdateOffer.PatchUpdate(emptyList()), offer(installed(), null, latest(revision = 1)))
    }

    @Test
    fun `android 11 and 12 are offered nothing, since they can't patch`() {
        for (sdk in listOf(30, 31, 32)) {
            assertEquals(UpdateOffer.None, offer(installed(versionCode = 99), info(2), latest(versionCode = 100), sdk))
            assertEquals(UpdateOffer.None, offer(installed(), info(1), latest(revision = 2), sdk))
            assertEquals(UpdateOffer.None, offer(installed(signer = shared), null, latest(), sdk))
        }
    }

    @Test
    fun `android 13 is offered updates`() {
        assertEquals(UpdateOffer.NewVersion("2.25.1"), offer(installed(versionCode = 99), info(2), latest(versionCode = 100), 33))
        assertEquals(UpdateOffer.NeedsMove, offer(installed(signer = shared), null, latest(), 36))
    }

    @Test
    fun `offer keys differ by revision`() {
        val o = UpdateOffer.PatchUpdate(emptyList())
        assertNotEquals(
            offerKey("com.whatsapp", latest(revision = 1), o),
            offerKey("com.whatsapp", latest(revision = 2), o),
        )
        assertEquals("com.whatsapp:100:2:PatchUpdate", offerKey("com.whatsapp", latest(), o))
    }

    @Test
    fun `new version and move keys ignore the revision`() {
        // A revision bump for the same WhatsApp is not news to someone already told about it.
        val newVersion = UpdateOffer.NewVersion("2.25.1")
        assertEquals("com.whatsapp:100:NewVersion", offerKey("com.whatsapp", latest(revision = 1), newVersion))
        assertEquals(
            offerKey("com.whatsapp", latest(revision = 1), newVersion),
            offerKey("com.whatsapp", latest(revision = 2), newVersion),
        )
        assertEquals("com.whatsapp:100:NeedsMove", offerKey("com.whatsapp", latest(revision = 1), UpdateOffer.NeedsMove))
        assertEquals(
            offerKey("com.whatsapp", latest(revision = 1), UpdateOffer.NeedsMove),
            offerKey("com.whatsapp", latest(revision = 3), UpdateOffer.NeedsMove),
        )
        assertNotEquals(
            offerKey("com.whatsapp", latest(versionCode = 100), newVersion),
            offerKey("com.whatsapp", latest(versionCode = 101), newVersion),
        )
    }
}
