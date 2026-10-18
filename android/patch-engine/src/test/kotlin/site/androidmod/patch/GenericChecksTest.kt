package site.androidmod.patch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GenericChecksTest {
    @get:Rule val tmp = TemporaryFolder()
    private val phone = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private var n = 0

    private fun apk(manifest: ByteArray, vararg entries: String): File =
        zip(File(tmp.root, "a${n++}.apk"), listOf("AndroidManifest.xml" to manifest) + entries.map { it to byteArrayOf(1) })

    private fun m(target: Int? = 34, vararg components: Pair<String, String>, shared: String? = null) =
        manifestWith("com.app", versionCode = 5, sharedUserId = shared, targetSdk = target, components = components.toList())

    private fun inspect(
        vararg apks: File, sdk: Int = 35, source: GenericSource = GenericSource.ORIGINAL, skip: Set<String> = emptySet(),
    ) = GenericChecks.inspect(apks.toList(), sdk, phone, source, skip)

    @Test fun cleanAppHasFactsAndNoReasons() {
        val f = inspect(apk(m(34), "lib/arm64-v8a/libapp.so", "lib/armeabi-v7a/libapp.so"))
        assertEquals("com.app", f.packageName)
        assertEquals(5L, f.versionCode)
        assertEquals(34, f.targetSdk)
        assertEquals(setOf("arm64-v8a", "armeabi-v7a"), f.libAbis)
        assertEquals(LibTarget("arm64-v8a", "arm64-v8a"), f.libTarget)
        assertTrue(f.cantPatch.isEmpty())
    }

    @Test fun pairipLibOrComponentFlagged() {
        assertEquals(listOf(CantPatch.PAIRIP), inspect(apk(m(), "lib/arm64-v8a/libpairipcore.so")).cantPatch)
        assertEquals(
            listOf(CantPatch.PAIRIP),
            inspect(apk(m(34, "activity" to "com.pairip.licensecheck.LicenseActivity"))).cantPatch,
        )
        assertTrue(inspect(apk(m(34, "activity" to "com.other.pairip.X"))).cantPatch.isEmpty())
    }

    @Test fun pairipLibInSplitFlagged() {
        val base = apk(m())
        val cfg = apk(manifestWith("com.app", versionCode = 5, split = "config.arm64_v8a"), "lib/arm64-v8a/libpairipcore.so")
        assertEquals(listOf(CantPatch.PAIRIP), inspect(base, cfg).cantPatch)
        // a lib the base was patched with is not the app's
        val ours = apk(m(), "lib/arm64-v8a/libpairipcore.so")
        assertTrue(inspect(ours, skip = setOf("lib/arm64-v8a/libpairipcore.so")).cantPatch.isEmpty())
    }

    @Test fun pairipApplicationClassFlagged() {
        fun app(name: String) = apk(manifestWith("com.app", versionCode = 5, targetSdk = 34, applicationName = name))
        assertEquals(listOf(CantPatch.PAIRIP), inspect(app("com.pairip.application.Application")).cantPatch)
        assertTrue(inspect(app("com.other.pairip.App")).cantPatch.isEmpty())
    }

    @Test fun unreadableManifestIsBadApk() {
        val junk = zip(File(tmp.root, "junk-manifest.apk"), listOf("AndroidManifest.xml" to byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        try {
            inspect(junk)
            org.junit.Assert.fail("expected BadApk")
        } catch (e: PatchException.BadApk) {
            // expected
        }
    }

    @Test fun nonPatchFailureIsWrappedAsBadApk() {
        // A zip whose entry name is malformed makes java.util.zip throw a RuntimeException, not an IOException.
        val f = File(tmp.root, "badname.apk")
        zip(f, listOf("AndroidManifest.xml" to byteArrayOf(1)))
        val bytes = f.readBytes()
        // corrupt the central directory's name length so the name runs past its record
        val cd = String(bytes, Charsets.ISO_8859_1).lastIndexOf("PK\u0001\u0002")
        bytes[cd + 28] = 0x7f
        f.writeBytes(bytes)
        try {
            inspect(f)
            org.junit.Assert.fail("expected BadApk")
        } catch (e: PatchException.BadApk) {
            // expected
        }
    }

    @Test fun sharedUserIdFlagged() {
        assertEquals(listOf(CantPatch.SHARED_USER_ID), inspect(apk(m(shared = "com.app.shared"))).cantPatch)
    }

    @Test fun targetSdkTable() {
        assertEquals(23, GenericChecks.minInstallableTargetSdk(34))
        assertEquals(24, GenericChecks.minInstallableTargetSdk(35))
        assertEquals(24, GenericChecks.minInstallableTargetSdk(37))
        assertEquals(0, GenericChecks.minInstallableTargetSdk(33))
    }

    @Test fun oldTargetFlaggedOnAndroid15() {
        assertEquals(listOf(CantPatch.TARGET_SDK_TOO_OLD), inspect(apk(m(23)), sdk = 35).cantPatch)
        assertTrue(inspect(apk(m(23)), sdk = 34).cantPatch.isEmpty())
        assertTrue(inspect(apk(m(24)), sdk = 35).cantPatch.isEmpty())
        // no targetSdk: falls back to minSdk, then 1
        assertEquals(1, inspect(apk(m(null)), sdk = 33).targetSdk)
        assertEquals(listOf(CantPatch.TARGET_SDK_TOO_OLD), inspect(apk(m(null)), sdk = 35).cantPatch)
    }

    @Test fun ourAssetsMeanAlreadyPatched() {
        for (asset in listOf("assets/patcher/patch-info.json", "assets/patcher/artifactory.json", "assets/paywall.json")) {
            val a = apk(m(), asset)
            assertEquals(asset, listOf(CantPatch.ALREADY_PATCHED), inspect(a).cantPatch)
            assertTrue(inspect(a, source = GenericSource.PATCHED).cantPatch.isEmpty())
        }
    }

    @Test fun reasonsComeOnceEachInEnumOrder() {
        val f = inspect(apk(m(20, shared = "x"), "lib/arm64-v8a/libpairipcore.so", "assets/paywall.json"))
        assertEquals(
            listOf(CantPatch.PAIRIP, CantPatch.SHARED_USER_ID, CantPatch.TARGET_SDK_TOO_OLD, CantPatch.ALREADY_PATCHED),
            f.cantPatch,
        )
    }

    @Test fun splitsMustMatchBase() {
        val base = apk(m())
        fun split(name: String?, pkg: String = "com.app", code: Int = 5) =
            apk(manifestWith(pkg, versionCode = code, split = name, targetSdk = 34))
        assertEquals("com.app", inspect(base, split("config.a"), split("config.b")).packageName)
        fun bad(vararg apks: File) = try { inspect(*apks); false } catch (e: PatchException.BadApk) { true }
        assertTrue("no split name", bad(base, split(null)))
        assertTrue("other package", bad(base, split("config.a", pkg = "com.other")))
        assertTrue("other versionCode", bad(base, split("config.a", code = 6)))
        assertTrue("duplicate split", bad(base, split("config.a"), split("config.a")))
        assertTrue("base is a split", bad(split("config.a"), split("config.b")))
        assertTrue("empty", bad())
        assertTrue("no manifest", bad(zip(File(tmp.root, "nm.apk"), listOf("x" to byteArrayOf(1)))))
        assertTrue("not a zip", bad(File(tmp.root, "junk.apk").also { it.writeText("junk") }))
        assertTrue("no versionCode", bad(apk(manifestWith("com.app", versionCode = null, targetSdk = 34))))
    }

    @Test fun libAbisCountSplits() {
        val base = apk(m())
        val cfg = apk(manifestWith("com.app", versionCode = 5, split = "config.arm64_v8a"), "lib/arm64-v8a/libapp.so")
        val f = inspect(base, cfg)
        assertEquals(setOf("arm64-v8a"), f.libAbis)
        assertEquals(LibTarget("arm64-v8a", "arm64-v8a"), f.libTarget)
        val hooked = apk(m(), "lib/arm64-v8a/libhook.so")
        assertEquals(emptySet<String>(), inspect(hooked, skip = setOf("lib/arm64-v8a/libhook.so")).libAbis)
    }

    @Test fun abiReasonsGoIntoCantPatch() {
        val f = GenericChecks.inspect(listOf(apk(m(), "lib/arm64-v8a/libapp.so")), 35, listOf("x86_64", "x86", "arm64-v8a"))
        assertNull(f.libTarget)
        assertEquals(listOf(CantPatch.NEEDS_TRANSLATION), f.cantPatch)
        assertFalse(f.cantPatch.isEmpty())
    }

    @Test fun unpatchableMessageListsReasons() {
        val e = PatchException.Unpatchable(listOf(CantPatch.PAIRIP, CantPatch.SHARED_USER_ID))
        assertEquals("cannot patch: PAIRIP, SHARED_USER_ID", e.message)
    }
}
