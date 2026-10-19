package site.androidmod.store.ui.library

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import site.androidmod.patch.GenericPatchInfo
import site.androidmod.store.data.ApiClient
import site.androidmod.store.generic.GenericApi
import site.androidmod.store.generic.GenericEntry
import site.androidmod.store.generic.GenericFlow
import site.androidmod.store.generic.GenericIntent
import site.androidmod.store.generic.GenericLibrarySource
import site.androidmod.store.generic.GenericPhase
import site.androidmod.store.generic.GenericRecord
import site.androidmod.store.generic.GenericRecords
import site.androidmod.store.generic.InstalledPackage
import site.androidmod.store.generic.InstalledState
import site.androidmod.store.generic.RefreshHint
import site.androidmod.store.generic.SavedApk
import site.androidmod.store.install.InstallOutcome

class GenericLibraryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    /** Real record files and a real GenericFlow; the phone (installed apps, their state, patch-info) is faked. */
    private inner class Fixture {
        val root = folder.newFolder()
        val records = GenericRecords(File(root, "generic"))

        /** What the phone has installed, by package. */
        val installed = HashMap<String, InstalledState>()
        var cert: String? = USER_CERT
        var apps: List<InstalledPackage> = emptyList()
        val infos = HashMap<String, GenericPatchInfo>()
        val infoReads = mutableListOf<String>()

        val flow = GenericFlow(
            records = records,
            installedState = { installed[it] },
            userCert = { cert },
            install = { _, _ -> InstallOutcome.Success },
            sha256 = { sha256(it.readBytes()) },
            now = { NOW },
            isBusy = { false },
        )

        val source = GenericLibrarySource(
            records = records,
            flow = flow,
            api = GenericApi(
                ApiClient(
                    client = OkHttpClient(),
                    baseUrl = { server.url("/").toString().trimEnd('/') },
                    token = { "tok" },
                    resumeDelayMillis = { 0L },
                ),
            ),
            listApps = { apps },
            readInfo = { apk ->
                infoReads += apk.name
                infos[apk.name]
            },
            userCert = { cert },
            now = { NOW },
        )

        /** A finished patch of [PKG]: our copy installed, its original saved, no intent. */
        fun patched(
            original: Boolean = true,
            bundleId: String = OLD_BUNDLE,
            expiresAt: Long? = NOW + 90 * DAY,
        ): GenericRecord {
            val saved = if (original) {
                val dir = records.originalDir(PKG).also { it.mkdirs() }
                File(dir, "base.apk").writeBytes(ORIGINAL_BYTES)
                listOf(SavedApk("base.apk", ORIGINAL_BYTES.size.toLong(), sha256(ORIGINAL_BYTES)))
            } else {
                emptyList()
            }
            val record = record().copy(original = saved, patchedBundleId = bundleId, tokenExpiresAt = expiresAt, patchedAt = NOW - DAY)
            records.put(record)
            installed[PKG] = InstalledState(setOf(USER_CERT), 7L, "built-sha")
            return record
        }
    }

    // ---- The Library's entries ----

    @Test
    fun orphan_patch_is_adopted_once() = runTest {
        val f = Fixture()
        val ours = app(PKG, setOf(USER_CERT), base = "ours-base.apk")
        // Signed with the user's key, but not a generic patch (the WhatsApp patch's info is another kind).
        val otherOfOurs = app("com.whatsapp", setOf(USER_CERT), base = "whatsapp-base.apk")
        val notOurs = app("com.other.app", setOf(DEV_CERT), base = "other-base.apk")
        f.apps = listOf(ours, otherOfOurs, notOurs)
        f.infos["ours-base.apk"] = INFO
        f.installed[PKG] = InstalledState(setOf(USER_CERT), 7L, "ours-sha")
        repeat(2) { server.enqueue(latest(NEW_BUNDLE)) }

        val first = f.source.entries()
        val adopted = f.records.get(PKG)!!
        val second = f.source.entries()

        assertEquals("only our generic patch is adopted", listOf(PKG), f.records.all().map { it.packageName })
        assertEquals(INFO.bundleId, adopted.patchedBundleId)
        assertTrue("no original was saved", adopted.original.isEmpty())
        assertNull(adopted.intent)
        assertEquals("the second refresh finds its record", adopted, f.records.get(PKG))
        assertEquals(
            "only user-signed apps without a record are read: ours once, the other one each refresh",
            listOf("ours-base.apk", "whatsapp-base.apk", "whatsapp-base.apk"),
            f.infoReads,
        )
        for (entries in listOf(first, second)) {
            val entry = entries.single()
            assertEquals(GenericPhase.Patched, entry.phase)
            assertEquals("its token expiry isn't known", RefreshHint.TokenUnknown, entry.hint)
            assertEquals(0L, entry.savedOriginalBytes)
        }
    }

    @Test
    fun nothing_is_adopted_without_the_users_key() = runTest {
        val f = Fixture()
        f.cert = null
        f.apps = listOf(app(PKG, setOf(USER_CERT), base = "ours-base.apk"))
        f.infos["ours-base.apk"] = INFO

        assertTrue(f.source.entries().isEmpty())
        assertTrue(f.records.all().isEmpty())
        assertTrue(f.infoReads.isEmpty())
    }

    @Test
    fun unreadable_patch_info_is_skipped() = runTest {
        val f = Fixture()
        f.apps = listOf(app(PKG, setOf(USER_CERT), base = "ours-base.apk"))
        val source = GenericLibrarySource(
            records = f.records,
            flow = f.flow,
            api = GenericApi(ApiClient(OkHttpClient(), baseUrl = { server.url("/").toString().trimEnd('/') }, token = { "tok" })),
            listApps = { f.apps },
            readInfo = { throw java.io.IOException("not a zip") },
            userCert = { USER_CERT },
            now = { NOW },
        )

        assertTrue(source.entries().isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun latest_not_called_without_generic_records() = runTest {
        val f = Fixture()
        f.apps = listOf(app("com.other.app", setOf(DEV_CERT)))

        assertTrue(f.source.entries().isEmpty())

        assertEquals(0, server.requestCount)
    }

    @Test
    fun latest_called_once_per_refresh_naming_no_app() = runTest {
        val f = Fixture()
        f.patched()
        val second = record().copy(packageName = "com.second.app", label = "Second")
        f.records.put(second)
        f.installed["com.second.app"] = InstalledState(setOf(DEV_CERT), 1L, "orig-sha")
        server.enqueue(latest(NEW_BUNDLE))

        assertEquals(2, f.source.entries().size)

        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/api/patch/generic/latest", request.target)
    }

    @Test
    fun newer_rules_hint_from_latest() = runTest {
        val f = Fixture()
        f.patched(bundleId = OLD_BUNDLE)
        server.enqueue(latest(NEW_BUNDLE))

        val entry = f.source.entries().single()

        assertEquals(GenericPhase.Patched, entry.phase)
        assertEquals(RefreshHint.NewerRules, entry.hint)
        assertEquals(ORIGINAL_BYTES.size.toLong(), entry.savedOriginalBytes)
    }

    @Test
    fun same_bundle_and_far_expiry_has_no_hint() = runTest {
        val f = Fixture()
        f.patched(bundleId = NEW_BUNDLE)
        server.enqueue(latest(NEW_BUNDLE))

        assertNull(f.source.entries().single().hint)
    }

    @Test
    fun store_update_required_sets_no_hint() = runTest {
        val f = Fixture()
        f.patched(bundleId = OLD_BUNDLE)
        server.enqueue(MockResponse(code = 426, body = """{"detail":"Update the store","min_store_version_code":99}"""))

        val entry = f.source.entries().single()

        assertEquals(GenericPhase.Patched, entry.phase)
        assertNull(entry.hint)
    }

    @Test
    fun failed_latest_still_warns_of_expiry() = runTest {
        val f = Fixture()
        f.patched(expiresAt = NOW + 10 * DAY)
        server.enqueue(MockResponse(code = 503, body = """{"detail":"Down"}"""))

        assertEquals(RefreshHint.TokenExpiring(10), f.source.entries().single().hint)
    }

    @Test
    fun a_flow_under_way_has_no_hint() = runTest {
        val f = Fixture()
        f.records.put(f.patched(expiresAt = NOW - DAY).copy(intent = GenericIntent.REFRESH))
        server.enqueue(latest(NEW_BUNDLE))

        val entry = f.source.entries().single()

        assertEquals(GenericIntent.REFRESH, entry.record.intent)
        assertNull(entry.hint)
    }

    @Test
    fun missing_key_shows_a_note_instead_of_attention() = runTest {
        val f = Fixture()
        f.patched()
        f.cert = null
        server.enqueue(latest(NEW_BUNDLE))

        val entry = f.source.entries().single()

        assertNull("unknown without the key, never Attention", entry.phase)
        assertNull(entry.hint)
        assertEquals("Can't be checked right now. Check your connection, then open the Library again.", genericStatusLine(entry))
        assertTrue(genericActions(entry).isEmpty())
    }

    @Test
    fun missing_key_keeps_phases_that_dont_need_it() = runTest {
        val f = Fixture()
        f.patched()
        f.cert = null
        f.installed.remove(PKG)
        server.enqueue(latest(NEW_BUNDLE))

        assertEquals(GenericPhase.Removed, f.source.entries().single().phase)
    }

    // ---- Status lines, hints and actions ----

    @Test
    fun status_line_for_each_phase() {
        val record = record()
        val under = record.copy(intent = GenericIntent.PATCH)
        fun line(phase: GenericPhase?, r: GenericRecord = record) = genericStatusLine(GenericEntry(r, phase, 0L, null))

        assertEquals("Patched on this phone · 7.1", line(GenericPhase.Patched))
        assertEquals("Removed from this phone", line(GenericPhase.Removed))
        assertEquals("Original installed", line(GenericPhase.Unpatched))
        assertEquals("Example was changed.", line(GenericPhase.Attention("Example was changed."), under))
        assertEquals("Example was changed.", line(GenericPhase.Attention("Example was changed.")))
        val unfinished = listOf(
            GenericPhase.SaveAndBuild,
            GenericPhase.RemoveOriginal(askedBefore = false),
            GenericPhase.RemoveOriginal(askedBefore = true),
            GenericPhase.InstallPatched,
            GenericPhase.RemovePatched(askedBefore = false),
            GenericPhase.InstallOriginal,
            GenericPhase.Restored,
            GenericPhase.InstallUpdate,
            // A Refresh whose new build is installed: the run screen finishes it.
            GenericPhase.Patched,
        )
        for (phase in unfinished) {
            assertEquals(phase.toString(), "Not finished — open to continue", line(phase, under))
        }
        assertEquals(
            "Can't be checked right now. Check your connection, then open the Library again.",
            line(null),
        )
    }

    @Test
    fun hint_texts() {
        assertEquals("Newer ad rules are out. Refresh to get them.", refreshHintText("Example", RefreshHint.NewerRules))
        assertEquals("Refresh within 12 days to keep Example working.", refreshHintText("Example", RefreshHint.TokenExpiring(12)))
        assertEquals("Refresh within 1 day to keep Example working.", refreshHintText("Example", RefreshHint.TokenExpiring(1)))
        assertEquals("Example has stopped working. Refresh to start it again.", refreshHintText("Example", RefreshHint.TokenExpired))
        assertEquals("Refresh Example to keep it working.", refreshHintText("Example", RefreshHint.TokenUnknown))
    }

    @Test
    fun update_hint_names_the_app() {
        assertEquals(
            "To update Example, put the original back, update it in Play, and patch it again.",
            genericUpdateHint("Example"),
        )
    }

    @Test
    fun delete_original_action_only_with_saved_original() {
        val saved = record().copy(original = listOf(SavedApk("base.apk", 1_572_864L, "ab")))
        val withOriginal = GenericEntry(saved, GenericPhase.Patched, 1_572_864L, null)
        val without = GenericEntry(record(), GenericPhase.Patched, 0L, null)

        assertEquals(
            listOf(GenericAction.REFRESH, GenericAction.PUT_BACK, GenericAction.REPORT, GenericAction.DELETE_ORIGINAL),
            genericActions(withOriginal),
        )
        assertEquals(listOf(GenericAction.REFRESH, GenericAction.PUT_BACK, GenericAction.REPORT), genericActions(without))
        assertEquals("Delete the saved original (1.5 MB)", genericActionLabel(GenericAction.DELETE_ORIGINAL, withOriginal))
    }

    @Test
    fun action_labels() {
        val entry = GenericEntry(record(), GenericPhase.Patched, 0L, null)
        assertEquals("Continue", genericActionLabel(GenericAction.CONTINUE, entry))
        assertEquals("Refresh", genericActionLabel(GenericAction.REFRESH, entry))
        assertEquals("Put the original back", genericActionLabel(GenericAction.PUT_BACK, entry))
        assertEquals("Report how it works", genericActionLabel(GenericAction.REPORT, entry))
    }

    @Test
    fun an_entry_with_an_intent_only_continues() {
        val saved = listOf(SavedApk("base.apk", 3L, "ab"))
        for (intent in GenericIntent.entries) {
            val record = record().copy(intent = intent, original = saved)
            for (phase in listOf(GenericPhase.Patched, GenericPhase.InstallPatched, GenericPhase.Attention("x"), null)) {
                assertEquals("$intent $phase", listOf(GenericAction.CONTINUE), genericActions(GenericEntry(record, phase, 3L, null)))
            }
        }
    }

    @Test
    fun other_finished_phases_offer_only_putting_a_saved_original_back() {
        val saved = record().copy(original = listOf(SavedApk("base.apk", 3L, "ab")))
        assertEquals(listOf(GenericAction.PUT_BACK), genericActions(GenericEntry(saved, GenericPhase.Removed, 3L, null)))
        assertTrue(genericActions(GenericEntry(record(), GenericPhase.Removed, 0L, null)).isEmpty())
        assertTrue(genericActions(GenericEntry(saved, GenericPhase.Unpatched, 3L, null)).isEmpty())
        // Putting back would leave it stuck at Attention: the installed copy is neither ours nor the original.
        assertTrue(genericActions(GenericEntry(saved, GenericPhase.Attention("changed"), 3L, null)).isEmpty())
    }

    @Test
    fun hints_show_only_under_a_finished_patch() {
        val finished = GenericEntry(record(), GenericPhase.Patched, 0L, RefreshHint.NewerRules)
        assertTrue(showsGenericHints(finished))
        assertFalse(showsGenericHints(finished.copy(record = record().copy(intent = GenericIntent.REFRESH))))
        assertFalse(showsGenericHints(finished.copy(phase = GenericPhase.Removed)))
        assertFalse(showsGenericHints(finished.copy(phase = null)))
    }

    private fun record() = GenericRecord(
        packageName = PKG,
        label = "Example",
        versionName = "7.1",
        versionCode = 7L,
        originalSigners = setOf(DEV_CERT),
        apkNames = listOf("base.apk"),
    )

    private fun app(pkg: String, signers: Set<String>, base: String = "$pkg-base.apk") =
        InstalledPackage(pkg, pkg, "1.0", 1L, listOf(File("/data/app/$pkg/$base")), signers)

    private fun latest(bundleId: String) = MockResponse(
        code = 200,
        body = """{"package":"generic","patcher_version":"g3","bundle_id":"$bundleId","sha256":"ab",
          "size":1234,"min_sdk":33,"min_store_version_code":40,"revision":2}""",
    )

    private companion object {
        const val PKG = "com.example.app"
        val USER_CERT = "11".repeat(32)
        val DEV_CERT = "22".repeat(32)
        const val OLD_BUNDLE = "old-bundle"
        const val NEW_BUNDLE = "new-bundle"
        const val DAY = 24L * 60 * 60 * 1000
        const val NOW = 1_800_000_000_000L
        val ORIGINAL_BYTES = "original base".toByteArray()
        val INFO = GenericPatchInfo(
            kind = "generic",
            patcherVersion = "g3",
            bundleId = "adopted-bundle",
            catalogFormat = 1,
            abi = "arm64-v8a",
            libDir = "lib/arm64-v8a",
            sdksPresent = "admob",
            added = emptyList(),
        )

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
