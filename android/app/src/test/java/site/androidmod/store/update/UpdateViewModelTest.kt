package site.androidmod.store.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import site.androidmod.store.data.ApiClient
import site.androidmod.store.data.StoreUpdateRepository
import site.androidmod.store.install.InstallFailure
import site.androidmod.store.install.InstallOutcome
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private val installed = InstalledBuild(versionCode = 389000, versionName = "1.0")

    /** Records what it was handed; the real installers need a device. */
    private class FakeInstaller(
        var allowed: Boolean = true,
        var outcome: InstallOutcome = InstallOutcome.Success,
    ) : SelfInstaller {
        val installed = mutableListOf<String>()
        override suspend fun canInstall() = allowed
        override suspend fun install(file: File): InstallOutcome {
            installed += file.readText()
            return outcome
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        directory = Files.createTempDirectory("downloads").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        directory.deleteRecursively()
        Dispatchers.resetMain()
    }

    /** How many bytes the fake disk is short of fitting any download; 0 means it fits. */
    private var shortOfSpace = 0L

    private fun viewModel(installer: SelfInstaller = FakeInstaller()) = UpdateViewModel(
        repository = StoreUpdateRepository(
            ApiClient(
                OkHttpClient(),
                baseUrl = { server.url("/").toString().trimEnd('/') },
                token = { null },
                reserveSpace = { _, _ -> shortOfSpace },
                resumeDelayMillis = { 0L },
            )
        ),
        installed = installed,
        directory = directory,
        installer = installer,
    )

    private fun publish(code: Long) = server.enqueue(MockResponse(code = 200, body = """
        {"available":true,"filename":"patched-store.apk","size":9,
         "updated_at":"2026-09-30 11:13","version_code":$code,"version_name":"1.1"}
    """.trimIndent()))

    /** Waits out the IO work the view model does off the test dispatcher. */
    private suspend fun UpdateViewModel.settled(): UpdateState =
        state.first { it.phase == UpdatePhase.Idle && (it.check != null || it.checkError != null) }

    @Test
    fun `checks on creation and offers a newer build`() = runTest {
        publish(389001)

        val state = viewModel().settled()

        assertEquals(UpdateCheck.Available(389001, "1.1", 9), state.check)
    }

    @Test
    fun `a failed check is reported without offering anything`() = runTest {
        server.enqueue(MockResponse(code = 500, body = """{"detail":"boom"}"""))

        val state = viewModel().settled()

        assertNull(state.check)
        assertEquals("boom", state.checkError)
    }

    @Test
    fun `update installs the downloaded build and then deletes it`() = runTest {
        publish(389001)
        server.enqueue(MockResponse(code = 200, body = "apk-bytes"))
        val installer = FakeInstaller()
        val model = viewModel(installer)
        model.settled()

        model.update()
        val state = model.state.first { it.phase == UpdatePhase.Idle && installer.installed.isNotEmpty() }

        assertEquals(listOf("apk-bytes"), installer.installed)
        assertNull(state.error)
        assertTrue("the 28 MB artifact must not be left behind", directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a refused install surfaces its message`() = runTest {
        publish(389001)
        server.enqueue(MockResponse(code = 200, body = "apk-bytes"))
        val installer = FakeInstaller(outcome = InstallOutcome.Failure("not ours", InstallFailure.UNTRUSTED_SIGNER))
        val model = viewModel(installer)
        model.settled()

        model.update()
        val state = model.state.first { it.error != null }

        assertEquals("not ours", state.error)
        assertEquals(UpdatePhase.Idle, state.phase)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `without install permission nothing is downloaded`() = runTest {
        publish(389001)
        val model = viewModel(FakeInstaller(allowed = false))
        model.settled()

        model.update()
        val state = model.state.first { it.needsInstallPermission }

        assertEquals(UpdatePhase.Idle, state.phase)
        assertEquals("only the version check reached the server", 1, server.requestCount)
    }

    @Test
    fun `a failed download surfaces the error and installs nothing`() = runTest {
        publish(389001)
        server.enqueue(MockResponse(code = 404, body = """{"detail":"The store app is not available yet"}"""))
        val installer = FakeInstaller()
        val model = viewModel(installer)
        model.settled()

        model.update()
        val state = model.state.first { it.error != null }

        assertEquals("The store app is not available yet", state.error)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun `update does nothing when no newer build is published`() = runTest {
        publish(389000)
        val installer = FakeInstaller()
        val model = viewModel(installer)
        assertEquals(UpdateCheck.UpToDate, model.settled().check)

        model.update()

        assertEquals(UpdatePhase.Idle, model.state.value.phase)
        assertEquals(1, server.requestCount)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun `an update that does not fit says so rather than blaming the network`() = runTest {
        publish(389001)
        server.enqueue(MockResponse(code = 200, body = "apk-bytes"))
        shortOfSpace = 20L * 1024 * 1024
        val installer = FakeInstaller()
        val model = viewModel(installer)
        model.settled()

        model.update()
        val state = model.state.first { it.error != null }

        assertEquals(
            "There isn't enough free space for this download. Free up 20 MB and try again.",
            state.error,
        )
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun `checking again clears an earlier check error`() = runTest {
        server.enqueue(MockResponse(code = 500, body = """{"detail":"boom"}"""))
        val model = viewModel()
        assertNotNull(model.settled().checkError)

        publish(389000)
        model.check()
        val state = model.state.first { it.check != null }

        assertNull(state.checkError)
        assertFalse(state.check is UpdateCheck.Available)
    }
}
