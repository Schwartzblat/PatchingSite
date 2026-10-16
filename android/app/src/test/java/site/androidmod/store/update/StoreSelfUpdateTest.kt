package site.androidmod.store.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
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

class StoreSelfUpdateTest {

    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private val installed = InstalledBuild(versionCode = 389000, versionName = "1.0")

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
        server = MockWebServer()
        server.start()
        directory = Files.createTempDirectory("downloads").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        directory.deleteRecursively()
    }

    private val repository
        get() = StoreUpdateRepository(
            ApiClient(
                OkHttpClient(),
                baseUrl = { server.url("/").toString().trimEnd('/') },
                token = { null },
                reserveSpace = { _, _ -> 0L },
                resumeDelayMillis = { 0L },
            ),
        )

    private fun publish(code: Long) = server.enqueue(
        MockResponse(
            code = 200,
            body = """{"available":true,"filename":"s.apk","size":9,"version_code":$code,"version_name":"1.1"}""",
        ),
    )

    @Test
    fun `nothing available returns null`() = runTest {
        publish(389000)
        val installer = FakeInstaller()

        assertNull(StoreSelfUpdate.run(repository, installed, installer, directory))

        assertEquals(1, server.requestCount)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun `available downloads and installs`() = runTest {
        publish(389001)
        server.enqueue(MockResponse(code = 200, body = "apk-bytes"))
        val installer = FakeInstaller()

        val outcome = StoreSelfUpdate.run(repository, installed, installer, directory)

        assertEquals(InstallOutcome.Success, outcome)
        assertEquals(listOf("apk-bytes"), installer.installed)
        assertTrue("the artifact must not be left behind", directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a refused install is returned and the file still deleted`() = runTest {
        publish(389001)
        server.enqueue(MockResponse(code = 200, body = "apk-bytes"))
        val refusal = InstallOutcome.Failure("not ours", InstallFailure.UNTRUSTED_SIGNER)

        val outcome = StoreSelfUpdate.run(repository, installed, FakeInstaller(outcome = refusal), directory)

        assertEquals(refusal, outcome)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `without install permission nothing is downloaded`() = runTest {
        publish(389001)

        assertNull(StoreSelfUpdate.run(repository, installed, FakeInstaller(allowed = false), directory))

        assertEquals(1, server.requestCount)
    }
}
