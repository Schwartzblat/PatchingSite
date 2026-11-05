package site.androidmod.store.data

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

class CatalogRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: CatalogRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = CatalogRepository(
            ApiClient(OkHttpClient(), baseUrl = { server.url("/").toString().trimEnd('/') }, token = { null })
        )
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `parses the catalogue including the renamed package field`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """
            [{"key":"whatsapp","name":"WhatsApp","package":"com.whatsapp",
              "features":["View Once Media Bypass"]}]
        """.trimIndent()))

        val apps = repository.apps()

        assertEquals(1, apps.size)
        assertEquals("com.whatsapp", apps[0].packageName)
        assertEquals(listOf("View Once Media Bypass"), apps[0].features)
    }

    @Test
    fun `parses a friends-only entry with its icon path`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """
            [{"key":"moovit","name":"Moovit","package":"com.tranzmate","features":[],
              "friends_only":true,"name_he":"x","features_he":[],
              "icon":"/api/apps/com.tranzmate/icon"}]
        """.trimIndent()))

        val app = repository.apps().single()

        assertTrue(app.friendsOnly)
        assertEquals("/api/apps/com.tranzmate/icon", app.icon)
    }

    @Test
    fun `a public entry is not friends-only`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """
            [{"key":"whatsapp","name":"WhatsApp","package":"com.whatsapp"}]
        """.trimIndent()))

        val app = repository.apps().single()

        assertEquals(false, app.friendsOnly)
        assertNull(app.icon)
    }

    @Test
    fun `parses versions and url-encodes the package`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """
            [{"id":"com.tranzmate:5.199.1.1804","version":"5.199.1.1804","type":"xapk",
              "filename":"patched_com.tranzmate-5.199.1.1804.xapk","size":42130483,
              "updated_at":"2026-09-02 00:57"}]
        """.trimIndent()))

        val versions = repository.versions("com.tranzmate")

        assertEquals(1, versions.size)
        assertEquals(42130483L, versions[0].size)
        assertEquals("/api/list/com.tranzmate", server.takeRequest().target)
    }

    @Test
    fun `parses the deployed server's older shape, which sends href instead of id`() = runTest {
        // androidmod.site is behind this repo. Requiring `id` would make the app
        // fail to list a single build against the live server.
        server.enqueue(MockResponse(code = 200, body = """
            [{"version":"5.189.0.1763","type":"xapk",
              "filename":"patched_com.tranzmate-5.189.0.1763.xapk","size":41372083,
              "updated_at":"2026-04-11 11:25","href":"/download/com.tranzmate/5.189.0.1763"}]
        """.trimIndent()))

        val versions = repository.versions("com.tranzmate")

        assertEquals(1, versions.size)
        assertNull(versions[0].id)
        assertEquals("5.189.0.1763", versions[0].key)
    }

    @Test
    fun `an empty catalogue is not an error`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "[]"))
        assertTrue(repository.apps().isEmpty())
    }

    @Test
    fun `a server error surfaces its message`() = runTest {
        server.enqueue(MockResponse(code = 404, body = """{"detail":"Unknown package"}"""))

        val error = runCatching { repository.versions("com.nope") }.exceptionOrNull()

        assertEquals("Unknown package", error?.message)
    }

    @Test
    fun `catalog reports on-device packages`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """
            [{"key":"whatsapp","name":"WhatsApp","package":"com.whatsapp","on_device":true},
             {"key":"mako","name":"Mako","package":"com.mako"}]
        """.trimIndent()))
        var seen: List<CatalogApp>? = null
        val reporting = CatalogRepository(
            ApiClient(OkHttpClient(), baseUrl = { server.url("/").toString().trimEnd('/') }, token = { null }),
        ) { seen = it }

        val apps = reporting.apps()

        assertEquals(apps, seen)
        assertEquals(listOf("com.whatsapp"), seen!!.filter { it.onDevice }.map { it.packageName })
    }

    @Test
    fun `a catalogue that lands after the session ended is not reported`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """[{"key":"a","name":"A","package":"com.a"}]"""))
        var generation = 0
        var seen: List<CatalogApp>? = null
        val reporting = CatalogRepository(
            ApiClient(OkHttpClient(), baseUrl = { server.url("/").toString().trimEnd('/') }, token = {
                generation++ // the sign-out lands while the request is being signed
                null
            }),
            session = { generation },
        ) { seen = it }

        val apps = reporting.apps()

        assertEquals(1, apps.size)
        assertNull(seen)
    }

    @Test
    fun `a catalogue in the same session is reported`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """[{"key":"a","name":"A","package":"com.a"}]"""))
        var seen: List<CatalogApp>? = null
        val reporting = CatalogRepository(
            ApiClient(OkHttpClient(), baseUrl = { server.url("/").toString().trimEnd('/') }, token = { null }),
            session = { 7 },
        ) { seen = it }

        reporting.apps()

        assertEquals(1, seen!!.size)
    }
}
