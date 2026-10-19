package site.androidmod.store.ui.generic

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import site.androidmod.patch.manifestWith
import site.androidmod.patch.zip
import site.androidmod.store.data.ApiClient
import site.androidmod.store.generic.GenericApi
import site.androidmod.store.generic.GenericIntent
import site.androidmod.store.generic.GenericRecord
import site.androidmod.store.generic.GenericRepository
import site.androidmod.store.generic.GenericStatus
import site.androidmod.store.generic.InstalledPackage
import site.androidmod.store.generic.PickerBadge
import site.androidmod.store.generic.WorksWithEntry
import site.androidmod.store.generic.refusedPackagesOf
import site.androidmod.store.patch.PatchApi
import site.androidmod.store.patch.SigningKeyStore

/**
 * The anti-goal: the installed apps, and the one picked, never leave the phone. The picker and
 * the confirm action are wired as production wires them, over a real ApiClient that would reach
 * this server, and the server must hear nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoNetworkTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        // Answers anything at once, so a stray call is counted rather than left hanging.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(code = 500)
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun installed(pkg: String, label: String, signers: Set<String> = setOf("d".repeat(64))): InstalledPackage {
        val dir = folder.newFolder(pkg)
        val base = zip(File(dir, "base.apk"), listOf("AndroidManifest.xml" to manifestWith(pkg, versionCode = 7, targetSdk = 34)))
        return InstalledPackage(pkg, label, "7.0", 7L, listOf(base), signers)
    }

    @Test
    fun picking_and_confirming_make_no_network_call() = runTest {
        val api = ApiClient(
            client = OkHttpClient(),
            baseUrl = { server.url("/").toString().trimEnd('/') },
            token = { "tok" },
            resumeDelayMillis = { 0L },
        )
        val generic = GenericRepository(
            GenericApi(api),
            initial = GenericStatus(
                enabled = true,
                worksWith = listOf(WorksWithEntry("com.example.tested", "Tested App", "No ads.", "1.0", "Pixel 9a")),
            ),
        )
        // A key on disk, as after any earlier patch; fetching one would be a network call.
        val keyDir = folder.newFolder("signing")
        val userCert = "e".repeat(64)
        File(keyDir, "signing-key.json").writeText(
            """{"password":"p","alias":"a","cert_sha256":"${userCert.uppercase()}","cert_sha1":"${"f".repeat(40)}"}""",
        )
        File(keyDir, "signing-key.p12").writeBytes(byteArrayOf(1, 2, 3))
        val signingKeys = SigningKeyStore(keyDir) { PatchApi(api).signingKey() }

        val apps = listOf(
            installed("com.example.tested", "Tested App"),
            installed("com.example.other", "Other App"),
            installed("com.whatsapp", "WhatsApp"),
            installed("com.example.mine", "Mine", signers = setOf(userCert)),
        )
        val picker = pickerViewModel(
            listApps = { apps },
            obb = { null },
            sharedCerts = { setOf("c".repeat(64)) },
            generic = generic,
            storePackages = { refusedPackagesOf(saved = setOf("com.keshet.mako.VOD"), loaded = emptySet()) },
            signingKeys = signingKeys,
            deviceSdk = 35,
            deviceAbis = listOf("arm64-v8a"),
            io = UnconfinedTestDispatcher(testScheduler),
        )

        val loaded = picker.state.first { state -> !state.loading && state.rows.none { it.badge == PickerBadge.Checking } }
        assertNull(loaded.error)
        val badges = loaded.rows.associate { it.app.packageName to it.badge }
        assertEquals(PickerBadge.Tested, badges["com.example.tested"])
        assertEquals(PickerBadge.NotTested, badges["com.example.other"])
        assertEquals(PickerBadge.InOurStore, badges["com.whatsapp"])
        assertEquals(PickerBadge.AlreadyPatched, badges["com.example.mine"])

        picker.onQuery("tested")
        val row = picker.state.value.rows.single()
        assertEquals(PickerTap.Confirm("com.example.tested"), pickerTap(row))

        val begun = mutableListOf<InstalledPackage>()
        val started = mutableListOf<Pair<String, String>>()
        val ok = confirmPatch(
            app = row.app,
            begin = { app ->
                begun += app
                GenericRecord(
                    packageName = app.packageName,
                    label = app.label,
                    versionName = app.versionName,
                    versionCode = app.versionCode,
                    originalSigners = app.signerSha256s,
                    apkNames = app.apks.map { it.name },
                    intent = GenericIntent.PATCH,
                )
            },
            start = { pkg, label -> started += pkg to label; true },
            io = UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(ok)
        assertEquals(listOf(row.app), begun)
        assertEquals(listOf("com.example.tested" to "Tested App"), started)
        assertEquals(0, server.requestCount)
    }
}
