package site.androidmod.store.ui.generic

import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import site.androidmod.patch.Capability
import site.androidmod.store.generic.GenericIntent
import site.androidmod.store.generic.GenericPhase
import site.androidmod.store.generic.GenericRecord
import site.androidmod.store.generic.SavedApk
import site.androidmod.store.install.InstallFailure
import site.androidmod.store.install.InstallOutcome
import site.androidmod.store.patch.PatchFailure
import site.androidmod.store.patch.PatchStage
import site.androidmod.store.patch.PatchState

@OptIn(ExperimentalCoroutinesApi::class)
class RunViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeFlow(var phase: GenericPhase?, intent: GenericIntent? = GenericIntent.PATCH) : RunFlow {
        var record: GenericRecord? = GenericRecord(
            packageName = PKG,
            label = "Example",
            versionName = "1.0",
            versionCode = 1L,
            originalSigners = setOf("aa"),
            apkNames = listOf("base.apk"),
            original = listOf(SavedApk("base.apk", 3L, "ab")),
            intent = intent,
        )
        var uninstallAnswer: String? = null
        var restoreAnswer: String? = null
        var stopAnswer = true
        var installPatchedOutcome: InstallOutcome = InstallOutcome.Success
        var installOriginalOutcome: InstallOutcome = InstallOutcome.Success
        var afterRestore: () -> Unit = {}

        /** Runs inside finish, after it is listed. */
        var afterFinish: () -> Unit = {}

        /** Whether the user's signing key is cached; without it our own copy reads as changed. */
        var keyKnown = true

        /** Thrown by every record write (askedToUninstall, finish, stop, startRestore), as a full disk would. */
        var writeError: Exception? = null

        /** Runs inside askedToUninstall, on the flow's thread. */
        var whileAsking: () -> Unit = {}

        /** Every call that could change something, in order; reads are not listed. */
        val calls = mutableListOf<String>()
        var reads = 0

        override fun record(packageName: String): GenericRecord? = record.also { reads++ }
        override fun phase(packageName: String): GenericPhase? = phase

        override fun askedToUninstall(packageName: String): String? {
            calls += "askedToUninstall"
            whileAsking()
            writeError?.let { throw it }
            return uninstallAnswer
        }

        override suspend fun installPatched(packageName: String): InstallOutcome {
            calls += "installPatched"
            return installPatchedOutcome
        }

        override suspend fun installOriginal(packageName: String): InstallOutcome {
            calls += "installOriginal"
            return installOriginalOutcome
        }

        override fun finish(packageName: String) {
            calls += "finish"
            writeError?.let { throw it }
            afterFinish()
        }

        override fun keyKnown(): Boolean = keyKnown

        override fun stop(packageName: String): Boolean {
            calls += "stop"
            writeError?.let { throw it }
            return stopAnswer
        }

        override fun startRestore(packageName: String): String? {
            calls += "startRestore"
            writeError?.let { throw it }
            if (restoreAnswer == null) afterRestore()
            return restoreAnswer
        }
    }

    private val jobs = MutableStateFlow<Map<String, PatchState>>(emptyMap())
    private val started = mutableListOf<Pair<String, String>>()
    private val forgotten = mutableListOf<String>()
    private var installAllowed = true

    /** The thread of every install-permission check. */
    private val installChecks = CopyOnWriteArrayList<Thread>()

    private val keptForReport = mutableListOf<GenericRecord>()

    private fun TestScope.model(flow: FakeFlow, io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)) = RunViewModel(
        packageName = PKG,
        flow = flow,
        jobs = jobs,
        startJob = { pkg, label -> started += pkg to label; true },
        forgetJob = { forgotten += it },
        canInstall = {
            installChecks += Thread.currentThread()
            installAllowed
        },
        keepForReport = { keptForReport += it },
        io = io,
    )

    private fun TestScope.eventsOf(model: RunViewModel): List<RunEvent> {
        val events = mutableListOf<RunEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.events.toList(events) }
        return events
    }

    @Test
    fun remove_fires_the_uninstall_only_when_the_flow_says_go() = runTest {
        val flow = FakeFlow(GenericPhase.RemoveOriginal(askedBefore = false))
        val model = model(flow)
        val events = eventsOf(model)

        val damaged = "The saved copies of Example are damaged, so it wasn't removed. Stop, then patch it again."
        flow.uninstallAnswer = damaged
        model.onRemove()
        assertEquals(listOf("askedToUninstall"), flow.calls)
        assertTrue(events.isEmpty())
        assertEquals(damaged, model.state.value.message)
        assertFalse(model.state.value.awaitingUninstall)

        flow.uninstallAnswer = null
        model.onRemove()
        assertEquals(listOf(RunEvent.LaunchUninstall(PKG)), events)
        assertTrue(model.state.value.awaitingUninstall)
        assertNull(model.state.value.message)

        // One dialog at a time.
        model.onRemove()
        assertEquals(listOf("askedToUninstall", "askedToUninstall"), flow.calls)
        assertEquals(1, events.size)
    }

    @Test
    fun a_second_remove_while_the_first_is_asked_sends_nothing() = runTest {
        val flow = FakeFlow(GenericPhase.RemoveOriginal(askedBefore = false))
        val asking = CountDownLatch(1)
        val release = CountDownLatch(1)
        flow.whileAsking = {
            asking.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val pool = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val model = model(flow, io = pool)
            val events = eventsOf(model)

            model.onRemove()
            assertTrue(asking.await(5, TimeUnit.SECONDS))
            // The second tap lands while the first is still being asked.
            model.onRemove()
            model.onStop()
            release.countDown()

            model.state.first { !it.busy && it.awaitingUninstall }
            assertEquals(listOf("askedToUninstall"), flow.calls)
            assertEquals(listOf(RunEvent.LaunchUninstall(PKG)), events)
        } finally {
            pool.close()
        }
    }

    @Test
    fun a_failed_write_shows_a_message_instead_of_crashing() = runTest {
        val failure = "That step didn't work. Try again."
        val removing = FakeFlow(GenericPhase.RemoveOriginal(askedBefore = false))
        val model = model(removing)
        val events = eventsOf(model)
        removing.writeError = IOException("No space left on device")

        model.onRemove()
        assertEquals(failure, model.state.value.message)
        assertFalse(model.state.value.busy)
        assertFalse(model.state.value.awaitingUninstall)
        model.onStop()
        assertEquals(failure, model.state.value.message)
        assertTrue(events.isEmpty())
        assertTrue(forgotten.isEmpty())

        val installing = FakeFlow(GenericPhase.InstallPatched)
        val putBack = model(installing)
        installing.writeError = IOException("No space left on device")
        putBack.onPutBack()
        assertEquals(failure, putBack.state.value.message)
        assertEquals(listOf("startRestore"), installing.calls)

        // Finishing is tried again on the next read; the phone already shows the swap done.
        val patched = FakeFlow(GenericPhase.Patched)
        patched.writeError = IOException("No space left on device")
        val done = model(patched)
        assertTrue(done.state.value.done)
        done.refresh()
        assertEquals(listOf("finish", "finish"), patched.calls)
    }

    @Test
    fun stop_and_put_back_wait_for_the_uninstall_dialog() = runTest {
        val flow = FakeFlow(GenericPhase.RemoveOriginal(askedBefore = false))
        val model = model(flow)
        val events = eventsOf(model)
        model.onRemove()
        flow.calls.clear()

        model.onStop()
        model.onPutBack()
        assertEquals(emptyList<String>(), flow.calls)

        // Back from the dialog, the app still there.
        flow.phase = GenericPhase.RemoveOriginal(askedBefore = true)
        model.onUninstallReturned()
        assertFalse(model.state.value.awaitingUninstall)
        assertTrue(model.state.value.stillInstalled)

        model.onStop()
        assertEquals(listOf("stop"), flow.calls)
        assertEquals(listOf(PKG), forgotten)
        assertEquals(RunEvent.Closed, events.last())
    }

    @Test
    fun a_refused_stop_says_so_and_stays() = runTest {
        val flow = FakeFlow(GenericPhase.Attention("Example was removed before its original was saved."))
        flow.stopAnswer = false
        val model = model(flow)
        val events = eventsOf(model)

        model.onStop()
        assertEquals(listOf("stop"), flow.calls)
        assertEquals("Example can't be stopped at this step.", model.state.value.message)
        assertTrue(events.isEmpty())
        assertTrue(forgotten.isEmpty())
    }

    @Test
    fun the_dialog_returning_with_the_app_gone_moves_on() = runTest {
        val flow = FakeFlow(GenericPhase.RemoveOriginal(askedBefore = false))
        val model = model(flow)
        model.onRemove()

        flow.phase = GenericPhase.InstallPatched
        model.onUninstallReturned()
        assertEquals(GenericPhase.InstallPatched, model.state.value.phase)
        assertFalse(model.state.value.stillInstalled)
        assertFalse(model.state.value.awaitingUninstall)
    }

    @Test
    fun opening_the_screen_only_reads() = runTest {
        // NavRequest.Generic opens this screen from an extra any app can send: it must start nothing.
        val phases = listOf(
            GenericPhase.SaveAndBuild,
            GenericPhase.RemoveOriginal(askedBefore = true),
            GenericPhase.InstallPatched,
            GenericPhase.RemovePatched(askedBefore = false),
            GenericPhase.InstallOriginal,
            GenericPhase.InstallUpdate,
            GenericPhase.Attention("Example was changed outside the store. Put the original back, or contact us."),
        )
        for (phase in phases) {
            val flow = FakeFlow(phase)
            val model = model(flow)
            val events = eventsOf(model)
            model.refresh()

            assertEquals("$phase", phase, model.state.value.phase)
            assertEquals("$phase", emptyList<String>(), flow.calls)
            assertTrue("$phase", events.isEmpty())
        }
        assertTrue(started.isEmpty())
        assertTrue(forgotten.isEmpty())
    }

    @Test
    fun patched_or_restored_finishes_then_shows_done() = runTest {
        val patched = FakeFlow(GenericPhase.Patched)
        val patchedModel = model(patched)
        assertEquals(listOf("finish"), patched.calls)
        assertTrue(patchedModel.state.value.done)

        val restored = FakeFlow(GenericPhase.Restored, GenericIntent.RESTORE)
        val restoredModel = model(restored)
        assertEquals(listOf("finish"), restored.calls)
        assertTrue(restoredModel.state.value.done)
        assertEquals("Example", restoredModel.state.value.label)

        // Finishing a restore forgets the app: still done.
        restored.record = null
        restored.phase = null
        restoredModel.refresh()
        assertTrue(restoredModel.state.value.done)
        assertEquals("Example", restoredModel.state.value.label)
    }

    @Test
    fun without_the_signing_key_attention_reads_as_unchecked() = runTest {
        // Without the cached key our own copy reads as changed outside the store: that can't be told now.
        val changed = GenericPhase.Attention("Example was changed outside the store. Uninstall it, then put the original back from the Library.")
        for (intent in listOf(GenericIntent.RESTORE, GenericIntent.REFRESH, GenericIntent.PATCH)) {
            val flow = FakeFlow(changed, intent)
            flow.keyKnown = false
            val model = model(flow)
            assertTrue("$intent", model.state.value.unchecked)
            assertNull("$intent", model.state.value.phase)
            assertFalse("$intent", model.state.value.loading)
            assertTrue("$intent", flow.calls.isEmpty())

            // The key is cached again (the Library fetched it): the step shows.
            flow.keyKnown = true
            model.refresh()
            assertFalse("$intent", model.state.value.unchecked)
            assertEquals("$intent", changed, model.state.value.phase)
        }

        // Only Attention depends on the key: every other step shows as it is.
        val removing = FakeFlow(GenericPhase.RemovePatched(askedBefore = false), GenericIntent.RESTORE)
        removing.keyKnown = false
        val model = model(removing)
        assertFalse(model.state.value.unchecked)
        assertEquals(GenericPhase.RemovePatched(false), model.state.value.phase)
    }

    @Test
    fun a_finished_restore_offers_the_report_with_the_record_read_before_finishing() = runTest {
        val flow = FakeFlow(GenericPhase.Restored, GenericIntent.RESTORE)
        val before = flow.record!!
        // Finishing a restore forgets the app, its record included.
        flow.afterFinish = {
            flow.record = null
            flow.phase = null
        }
        val model = model(flow)
        val events = eventsOf(model)
        assertEquals(listOf("finish"), flow.calls)
        assertTrue(model.state.value.done)
        assertEquals(before, model.state.value.restoredRecord)

        // Still offered after the record is gone.
        model.refresh()
        assertEquals(before, model.state.value.restoredRecord)
        assertTrue(keptForReport.isEmpty())

        model.onReport()
        assertEquals(listOf(before), keptForReport)
        assertEquals(listOf(RunEvent.OpenReport(PKG)), events)
    }

    @Test
    fun a_finished_patch_offers_no_report_on_the_run_screen() = runTest {
        // The Library offers it on the Patched entry, whose record stays.
        val flow = FakeFlow(GenericPhase.Patched)
        val model = model(flow)
        val events = eventsOf(model)
        assertTrue(model.state.value.done)
        assertNull(model.state.value.restoredRecord)

        model.onReport()
        assertTrue(keptForReport.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun runStateCarriesTheMix() = runTest {
        val both = FakeFlow(GenericPhase.SaveAndBuild)
        both.record = both.record!!.copy(capabilities = listOf("ads", "iap"))
        assertEquals(setOf(Capability.ADS, Capability.IAP), model(both).state.value.capabilities)

        val purchases = FakeFlow(GenericPhase.SaveAndBuild)
        purchases.record = purchases.record!!.copy(capabilities = listOf("iap"))
        assertEquals(setOf(Capability.IAP), model(purchases).state.value.capabilities)

        // A record from before choices carries none: ads-only, as every start default is.
        val legacy = FakeFlow(GenericPhase.SaveAndBuild)
        assertEquals(setOf(Capability.ADS), model(legacy).state.value.capabilities)

        // Nothing under way (no record) keeps the ads-only default too.
        val none = FakeFlow(null)
        none.record = null
        assertEquals(setOf(Capability.ADS), model(none).state.value.capabilities)
    }

    @Test
    fun nothing_under_way_is_not_done() = runTest {
        val flow = FakeFlow(null)
        flow.record = null
        val model = model(flow)
        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.done)
        assertNull(model.state.value.phase)
        assertEquals(PKG, model.state.value.label)
        assertTrue(flow.calls.isEmpty())
    }

    @Test
    fun put_back_at_install_patched_restores_then_installs_the_original() = runTest {
        // Offered at this step whatever this screen saw: the original is saved, and nothing is installed.
        val fresh = FakeFlow(GenericPhase.InstallPatched)
        fresh.afterRestore = { fresh.phase = GenericPhase.InstallOriginal }
        model(fresh).onPutBack()
        assertEquals(listOf("startRestore", "installOriginal"), fresh.calls)

        val flow = FakeFlow(GenericPhase.InstallPatched)
        flow.installPatchedOutcome = InstallOutcome.Failure("The install was cancelled.", InstallFailure.ABORTED)
        val model = model(flow)

        model.onInstallPatched()
        assertEquals(listOf("installPatched"), flow.calls)
        assertEquals("The install was cancelled.", model.state.value.message)
        assertFalse(model.state.value.busy)

        flow.restoreAnswer = "The saved original is damaged, so it can't be put back."
        model.onPutBack()
        assertEquals(listOf("installPatched", "startRestore"), flow.calls)
        assertEquals("The saved original is damaged, so it can't be put back.", model.state.value.message)

        flow.restoreAnswer = null
        flow.afterRestore = {
            flow.record = flow.record!!.copy(intent = GenericIntent.RESTORE)
            flow.phase = GenericPhase.InstallOriginal
        }
        model.onPutBack()
        assertEquals(listOf("installPatched", "startRestore", "startRestore", "installOriginal"), flow.calls)
        assertEquals(GenericIntent.RESTORE, model.state.value.intent)
        assertNull(model.state.value.message)
    }

    @Test
    fun installs_wait_for_the_install_permission() = runTest {
        val flow = FakeFlow(GenericPhase.InstallPatched)
        installAllowed = false
        val model = model(flow)

        model.onInstallPatched()
        assertTrue(model.state.value.needsInstallPermission)
        assertTrue(flow.calls.isEmpty())

        installAllowed = true
        flow.phase = GenericPhase.Patched
        model.onInstallPatched()
        assertFalse(model.state.value.needsInstallPermission)
        assertEquals(listOf("installPatched", "finish"), flow.calls)
        assertTrue(model.state.value.done)
    }

    @Test
    fun remove_waits_for_the_install_permission() = runTest {
        // Android removes the app at once; without the permission neither copy could go back in.
        for (phase in listOf(GenericPhase.RemoveOriginal(askedBefore = false), GenericPhase.RemovePatched(askedBefore = false))) {
            val flow = FakeFlow(phase)
            installAllowed = false
            val model = model(flow)
            val events = eventsOf(model)

            model.onRemove()
            assertTrue("$phase", model.state.value.needsInstallPermission)
            // askedToUninstall is what writes uninstallAskedAt: never called, so nothing was written.
            assertTrue("$phase", flow.calls.isEmpty())
            assertTrue("$phase", events.isEmpty())
            assertFalse("$phase", model.state.value.awaitingUninstall)
            assertFalse("$phase", model.state.value.busy)

            installAllowed = true
            model.onRemove()
            assertFalse("$phase", model.state.value.needsInstallPermission)
            assertEquals("$phase", listOf("askedToUninstall"), flow.calls)
            assertEquals("$phase", listOf(RunEvent.LaunchUninstall(PKG)), events)
        }
    }

    @Test
    fun allowing_installs_in_settings_clears_the_banner_on_resume() = runTest {
        // "Open settings", then Back: the screen resumes and reads again.
        val flow = FakeFlow(GenericPhase.RemoveOriginal(askedBefore = false))
        installAllowed = false
        val model = model(flow)
        val events = eventsOf(model)
        model.onRemove()
        assertTrue(model.state.value.needsInstallPermission)

        // Back without allowing it: the banner stays.
        model.refresh()
        assertTrue(model.state.value.needsInstallPermission)

        // Allowed: it goes, and nothing else happens; Remove waits for the customer's tap.
        installAllowed = true
        model.refresh()
        assertFalse(model.state.value.needsInstallPermission)
        assertTrue(flow.calls.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun the_permission_is_checked_again_on_io() = runTest {
        val pool = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val poolThread = withContext(pool) { Thread.currentThread() }
            installAllowed = false
            val model = model(FakeFlow(GenericPhase.RemoveOriginal(askedBefore = false)), io = pool)
            model.state.first { !it.loading }
            model.onRemove()
            model.state.first { it.needsInstallPermission && !it.busy }

            installChecks.clear()
            installAllowed = true
            model.refresh()
            model.state.first { !it.needsInstallPermission }
            assertTrue(installChecks.isNotEmpty())
            assertTrue(installChecks.all { it === poolThread })
        } finally {
            pool.close()
        }
    }

    @Test
    fun a_read_without_the_banner_does_not_check_the_permission() = runTest {
        val flow = FakeFlow(GenericPhase.RemoveOriginal(askedBefore = false))
        val model = model(flow)
        model.refresh()
        assertTrue(installChecks.isEmpty())
        assertFalse(model.state.value.needsInstallPermission)
    }

    @Test
    fun an_obb_refusal_removes_nothing_and_stop_still_works() = runTest {
        val refusal = "Example keeps extra data in Android/obb, which Android deletes with the app, so it wasn't removed. " +
            "Stop to keep Example as it is."
        val flow = FakeFlow(GenericPhase.RemoveOriginal(askedBefore = false))
        flow.uninstallAnswer = refusal
        val model = model(flow)
        val events = eventsOf(model)

        model.onRemove()
        assertEquals(refusal, model.state.value.message)
        assertFalse(model.state.value.awaitingUninstall)
        assertTrue(events.isEmpty())

        model.onStop()
        assertEquals(listOf("askedToUninstall", "stop"), flow.calls)
        assertEquals(listOf(RunEvent.Closed), events)
        assertEquals(listOf(PKG), forgotten)
    }

    @Test
    fun a_job_ending_rereads_the_phase_and_try_again_restarts_it() = runTest {
        jobs.value = mapOf(PKG to PatchState.Running(PatchStage.SavingOriginal(1, 4)))
        val flow = FakeFlow(GenericPhase.SaveAndBuild)
        val model = model(flow)
        assertEquals(GenericPhase.SaveAndBuild, model.state.value.phase)
        val readsWhileRunning = flow.reads

        // Progress alone does not re-read the phone.
        jobs.value = mapOf(PKG to PatchState.Running(PatchStage.SavingOriginal(2, 4)))
        assertEquals(readsWhileRunning, flow.reads)
        assertEquals(PatchState.Running(PatchStage.SavingOriginal(2, 4)), model.state.value.job)

        flow.phase = GenericPhase.RemoveOriginal(askedBefore = false)
        jobs.value = mapOf(PKG to PatchState.Ready(File("/files/generic/$PKG/built"), "1.0"))
        assertEquals(GenericPhase.RemoveOriginal(askedBefore = false), model.state.value.phase)

        val failed = PatchState.Failed(PatchFailure.NETWORK, "Network error. Check your connection and try again.")
        flow.phase = GenericPhase.SaveAndBuild
        jobs.value = mapOf(PKG to failed)
        assertEquals(failed, model.state.value.job)
        assertEquals(GenericPhase.SaveAndBuild, model.state.value.phase)

        model.onRetry()
        assertEquals(listOf(PKG to "Example"), started)
        assertTrue(flow.calls.isEmpty())
    }

    private companion object {
        const val PKG = "com.example.app"
    }
}
