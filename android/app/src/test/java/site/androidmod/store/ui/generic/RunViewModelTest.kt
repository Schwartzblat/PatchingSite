package site.androidmod.store.ui.generic

import java.io.File
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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

        /** Every call that could change something, in order; reads are not listed. */
        val calls = mutableListOf<String>()
        var reads = 0

        override fun record(packageName: String): GenericRecord? = record.also { reads++ }
        override fun phase(packageName: String): GenericPhase? = phase

        override fun askedToUninstall(packageName: String): String? {
            calls += "askedToUninstall"
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
        }

        override fun stop(packageName: String): Boolean {
            calls += "stop"
            return stopAnswer
        }

        override fun startRestore(packageName: String): String? {
            calls += "startRestore"
            if (restoreAnswer == null) afterRestore()
            return restoreAnswer
        }
    }

    private val jobs = MutableStateFlow<Map<String, PatchState>>(emptyMap())
    private val started = mutableListOf<Pair<String, String>>()
    private val forgotten = mutableListOf<String>()
    private var installAllowed = true

    private fun TestScope.model(flow: FakeFlow) = RunViewModel(
        packageName = PKG,
        flow = flow,
        jobs = jobs,
        startJob = { pkg, label -> started += pkg to label; true },
        forgetJob = { forgotten += it },
        canInstall = { installAllowed },
        io = UnconfinedTestDispatcher(testScheduler),
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
    fun put_back_after_a_failed_install_restores_then_installs_the_original() = runTest {
        val flow = FakeFlow(GenericPhase.InstallPatched)
        flow.installPatchedOutcome = InstallOutcome.Failure("The install was cancelled.", InstallFailure.ABORTED)
        val model = model(flow)

        model.onInstallPatched()
        assertEquals(listOf("installPatched"), flow.calls)
        assertEquals("The install was cancelled.", model.state.value.message)
        assertTrue(model.state.value.installFailed)
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
