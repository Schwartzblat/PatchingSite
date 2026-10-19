package site.androidmod.store.ui.generic

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import site.androidmod.patch.ApkFacts
import site.androidmod.patch.CantPatch
import site.androidmod.store.generic.InstalledPackage
import site.androidmod.store.generic.PickerBadge
import site.androidmod.store.generic.PickerRow

@OptIn(ExperimentalCoroutinesApi::class)
class PickerViewModelTest {

    private lateinit var pool: ExecutorCoroutineDispatcher

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    }

    @After
    fun tearDown() {
        pool.close()
        Dispatchers.resetMain()
    }

    private fun app(pkg: String, label: String, signers: Set<String> = setOf("aa")) =
        InstalledPackage(pkg, label, "1.0", 1L, listOf(File("/data/app/$pkg/base.apk")), signers)

    private fun facts(pkg: String, vararg reasons: CantPatch) =
        ApkFacts(pkg, 1L, "1.0", 34, emptySet(), null, reasons.toList())

    private val apps = listOf(
        app("com.example.tested", "Tested App"),
        app("com.example.other", "Other App"),
        app("com.example.pairip", "Guarded App"),
        app("com.whatsapp", "WhatsApp"),
        app("com.example.mine", "Mine", signers = setOf("user")),
    )

    private fun inspectFake(apks: List<File>): ApkFacts {
        val pkg = apks.first().parentFile!!.name
        return if (pkg == "com.example.pairip") facts(pkg, CantPatch.PAIRIP) else facts(pkg)
    }

    private fun viewModel(
        listApps: () -> List<InstalledPackage> = { apps },
        inspect: (List<File>) -> ApkFacts = ::inspectFake,
        storePackages: () -> Set<String> = { setOf("com.whatsapp", "com.keshet.mako.VOD") },
        tested: () -> Set<String>? = { setOf("com.example.tested") },
        io: CoroutineDispatcher = pool,
    ) = PickerViewModel(
        listApps = listApps,
        inspect = inspect,
        obb = { false },
        storePackages = storePackages,
        tested = tested,
        sharedCerts = { setOf("shared") },
        userCert = { "user" },
        io = io,
    )

    private fun PickerState.badgeOf(pkg: String) = rows.single { it.app.packageName == pkg }.badge

    @Test
    fun rows_start_checking_then_get_badges() = runTest {
        val release = CountDownLatch(1)
        val model = viewModel(inspect = { apks ->
            release.await(5, TimeUnit.SECONDS)
            inspectFake(apks)
        })

        val listed = model.state.first { !it.loading }
        assertNull(listed.error)
        assertEquals(apps.size, listed.rows.size)
        assertEquals(PickerBadge.Checking, listed.badgeOf("com.example.tested"))
        assertEquals(PickerBadge.Checking, listed.badgeOf("com.example.other"))
        assertEquals(PickerBadge.Checking, listed.badgeOf("com.example.pairip"))
        // Known without reading their files.
        assertEquals(PickerBadge.InOurStore, listed.badgeOf("com.whatsapp"))
        assertEquals(PickerBadge.AlreadyPatched, listed.badgeOf("com.example.mine"))

        release.countDown()
        val done = model.state.first { state -> state.rows.none { it.badge == PickerBadge.Checking } }
        assertEquals(PickerBadge.Tested, done.badgeOf("com.example.tested"))
        assertEquals(PickerBadge.NotTested, done.badgeOf("com.example.other"))
        assertEquals(
            PickerBadge.CantPatch(listOf("It uses Google's anti-tamper check (PairIP), so a patched copy won't start.")),
            done.badgeOf("com.example.pairip"),
        )
        assertEquals(PickerBadge.InOurStore, done.badgeOf("com.whatsapp"))
        assertEquals(PickerBadge.AlreadyPatched, done.badgeOf("com.example.mine"))
    }

    @Test
    fun inspect_runs_two_at_a_time() = runTest {
        val many = (1..6).map { app("com.example.app$it", "App $it") }
        val running = AtomicInteger()
        val most = AtomicInteger()
        val calls = AtomicInteger()
        val twoStarted = CountDownLatch(2)
        val release = CountDownLatch(1)
        val model = viewModel(listApps = { many }, tested = { emptySet() }, inspect = { apks ->
            val now = running.incrementAndGet()
            most.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            calls.incrementAndGet()
            twoStarted.countDown()
            release.await(5, TimeUnit.SECONDS)
            running.decrementAndGet()
            inspectFake(apks)
        })

        assertTrue(twoStarted.await(5, TimeUnit.SECONDS))
        // Room for a third to start, if anything would let it.
        Thread.sleep(200)
        assertEquals(2, most.get())
        assertEquals(2, calls.get())

        release.countDown()
        val done = model.state.first { state -> state.rows.none { it.badge == PickerBadge.Checking } }
        assertEquals(6, calls.get())
        assertEquals(2, most.get())
        assertTrue(done.rows.all { it.badge == PickerBadge.NotTested })
    }

    @Test
    fun query_filters_rows() = runTest {
        val model = viewModel()
        model.state.first { state -> !state.loading && state.rows.none { it.badge == PickerBadge.Checking } }

        model.onQuery("APP")
        assertEquals("APP", model.state.value.query)
        assertEquals(listOf("Guarded App", "Other App", "Tested App", "WhatsApp"), model.state.value.rows.map { it.app.label })

        // The package matches too.
        model.onQuery("whatsapp")
        assertEquals(listOf("WhatsApp"), model.state.value.rows.map { it.app.label })

        model.onQuery("nothing like it")
        assertTrue(model.state.value.rows.isEmpty())

        model.onQuery("")
        assertEquals(listOf("Guarded App", "Mine", "Other App", "Tested App", "WhatsApp"), model.state.value.rows.map { it.app.label })
    }

    @Test
    fun missing_store_list_shows_error_and_no_rows() = runTest {
        val cases = listOf<Pair<String, Pair<() -> Set<String>, () -> Set<String>?>>>(
            "no catalogue loaded yet" to ({ emptySet<String>() } to { setOf("com.example.tested") }),
            "no generic status yet" to ({ setOf("com.whatsapp") } to { null }),
        )
        for ((name, sources) in cases) {
            var listed = false
            val model = viewModel(
                listApps = { listed = true; apps },
                storePackages = sources.first,
                tested = sources.second,
                io = UnconfinedTestDispatcher(testScheduler),
            )
            val state = model.state.first { !it.loading }
            assertEquals(name, "Open the Apps tab again to load the store's list, then try again.", state.error)
            assertTrue(name, state.rows.isEmpty())
            assertFalse(name, listed)

            model.onQuery("app")
            assertTrue(name, model.state.value.rows.isEmpty())
        }
    }

    @Test
    fun an_empty_tested_list_still_lists_apps() = runTest {
        val model = viewModel(tested = { emptySet() }, io = UnconfinedTestDispatcher(testScheduler))
        val state = model.state.first { !it.loading && it.rows.none { row -> row.badge == PickerBadge.Checking } }
        assertNull(state.error)
        assertEquals(PickerBadge.NotTested, state.badgeOf("com.example.tested"))
    }

    @Test
    fun tapping_a_row_goes_where_its_badge_says() {
        val app = app("com.example.app", "Example")
        assertEquals(PickerTap.StorePage("com.example.app"), pickerTap(PickerRow(app, PickerBadge.InOurStore)))
        assertEquals(PickerTap.Confirm("com.example.app"), pickerTap(PickerRow(app, PickerBadge.Tested)))
        assertEquals(PickerTap.Confirm("com.example.app"), pickerTap(PickerRow(app, PickerBadge.NotTested)))
        assertEquals(PickerTap.None, pickerTap(PickerRow(app, PickerBadge.Checking)))
        assertEquals(PickerTap.None, pickerTap(PickerRow(app, PickerBadge.AlreadyPatched)))
        assertEquals(PickerTap.None, pickerTap(PickerRow(app, PickerBadge.CantPatch(listOf("It's in our store.")))))
    }

    @Test
    fun badge_words_are_the_specs() {
        assertEquals("Tested", pickerBadgeText(PickerBadge.Tested))
        assertEquals("Not tested", pickerBadgeText(PickerBadge.NotTested))
        assertEquals("In our store", pickerBadgeText(PickerBadge.InOurStore))
        assertEquals("Already patched", pickerBadgeText(PickerBadge.AlreadyPatched))
        assertEquals("Can't patch", pickerBadgeText(PickerBadge.CantPatch(listOf("It has already been patched."))))
        assertEquals("Checking…", pickerBadgeText(PickerBadge.Checking))
    }
}
