package site.androidmod.store.ui.generic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import site.androidmod.patch.Capability
import site.androidmod.store.StoreApp
import site.androidmod.store.generic.GenericFlow
import site.androidmod.store.generic.GenericIntent
import site.androidmod.store.generic.GenericPhase
import site.androidmod.store.generic.GenericRecord
import site.androidmod.store.generic.GenericRecords
import site.androidmod.store.generic.capabilitiesOf
import site.androidmod.store.install.InstallOutcome
import site.androidmod.store.patch.PatchMode
import site.androidmod.store.patch.PatchRegistry
import site.androidmod.store.patch.PatchService
import site.androidmod.store.patch.PatchState

/** What the run screen drives of an app's generic swap. Every call but the two installs blocks. */
interface RunFlow {
    fun record(packageName: String): GenericRecord?
    fun phase(packageName: String): GenericPhase?
    fun askedToUninstall(packageName: String): String?
    suspend fun installPatched(packageName: String): InstallOutcome
    suspend fun installOriginal(packageName: String): InstallOutcome
    fun finish(packageName: String)
    fun stop(packageName: String): Boolean
    fun startRestore(packageName: String): String?

    /** Whether the user's signing key is cached; without it our own copy reads as changed outside the store. */
    fun keyKnown(): Boolean
}

/** The store's [GenericFlow], with the record read from [records]. */
fun runFlowOf(flow: GenericFlow, records: GenericRecords): RunFlow = object : RunFlow {
    override fun record(packageName: String) = records.get(packageName)
    override fun phase(packageName: String) = flow.phase(packageName)
    override fun askedToUninstall(packageName: String) = flow.askedToUninstall(packageName)
    override suspend fun installPatched(packageName: String) = flow.installPatched(packageName)
    override suspend fun installOriginal(packageName: String) = flow.installOriginal(packageName)
    override fun finish(packageName: String) = flow.finish(packageName)
    override fun stop(packageName: String) = flow.stop(packageName)
    override fun startRestore(packageName: String) = flow.startRestore(packageName)
    override fun keyKnown() = flow.keyKnown()
}

sealed interface RunEvent {
    /** Ask Android to remove the app now: [RunFlow.askedToUninstall] said go. */
    data class LaunchUninstall(val packageName: String) : RunEvent

    /** The swap was stopped: leave the screen. */
    data object Closed : RunEvent

    /** Open "Report how it works" for the app just put back. */
    data class OpenReport(val packageName: String) : RunEvent
}

data class RunState(
    /** Until the record and the phone are first read. */
    val loading: Boolean = true,
    val label: String = "",
    /** The record's; null when nothing is under way. */
    val intent: GenericIntent? = null,
    /**
     * The mix the build was asked for: the record's wire names, ads-only when it carries none
     * (a record from before choices, or none yet). Names the run screen's build step.
     */
    val capabilities: Set<Capability> = setOf(Capability.ADS),
    /** Null when the app has no record, or when [unchecked]. */
    val phase: GenericPhase? = null,
    /** The record lists the saved original. */
    val originalSaved: Boolean = false,
    /** The job's progress or failure (PatchRegistry); only the job's, never the screen's own steps. */
    val job: PatchState? = null,
    /** Android's uninstall dialog is up: Stop and Put the original back wait for its answer. */
    val awaitingUninstall: Boolean = false,
    /** The uninstall was asked for, and the app is still installed. */
    val stillInstalled: Boolean = false,
    /** One of the screen's steps is running: a flow call or an install. */
    val busy: Boolean = false,
    val needsInstallPermission: Boolean = false,
    /** Why the last step didn't happen. */
    val message: String? = null,
    /** The swap finished: Patched or Restored. Stays once set, though finishing a restore forgets the app. */
    val done: Boolean = false,
    /**
     * The step reads as changed outside the store while the user's signing key isn't cached, and
     * without the key our own copy reads that way too: it can't be told now, as in the Library.
     */
    val unchecked: Boolean = false,
    /** The record of the app just put back, read before finishing forgot it: Done offers the report with it. */
    val restoredRecord: GenericRecord? = null,
)

/**
 * The run screen of [packageName]'s generic swap. Opening it only reads: the phase comes from the
 * record and the phone, re-read on every resume ([refresh]) and when the job stops running. Each
 * step runs only from the customer's tap, and Android's uninstall dialog only after
 * [RunFlow.askedToUninstall] says go. Flow calls run on [io]; the job's state is read from [jobs]
 * and never written here, apart from forgetting a stopped app's.
 */
class RunViewModel(
    val packageName: String,
    private val flow: RunFlow = StoreApp.instance.container.let { runFlowOf(it.genericFlow, it.genericRecords) },
    private val jobs: StateFlow<Map<String, PatchState>> = PatchRegistry.states,
    /** Starts the job; false when Android refused (see PatchService.start). */
    private val startJob: (pkg: String, label: String) -> Boolean = { pkg, label ->
        PatchService.start(StoreApp.instance, pkg, PatchMode.GENERIC, label)
    },
    private val forgetJob: (String) -> Unit = PatchRegistry::clear,
    /** Whether an install can start: the session installer needs "install unknown apps". Blocks. */
    private val canInstall: () -> Boolean = StoreApp.instance.container.let { container ->
        { container.installer() !== container.sessionInstaller || container.sessionInstaller.canInstall() }
    },
    /** Hands the record of the app just put back to the report screen, which finds it there. */
    private val keepForReport: (GenericRecord) -> Unit = StoreApp.instance.container.restoredRecords::keep,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(RunState(label = packageName))
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val _events = Channel<RunEvent>(Channel.BUFFERED)
    val events: Flow<RunEvent> = _events.receiveAsFlow()

    // One read at a time, so an earlier read can never land after a later one.
    private val reading = Mutex()

    init {
        viewModelScope.launch {
            var first = true
            jobs.map { it[packageName] }.distinctUntilChanged().collect { job ->
                _state.update { it.copy(job = job) }
                // Progress changes nothing on the phone; the job ending does.
                if (first || job !is PatchState.Running) read()
                first = false
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { read() }
    }

    /**
     * "Remove <App>" or "Remove the patched copy". Android removes the app at once, so this first
     * makes sure the store may install: without that, neither the patched copy nor the saved
     * original could go back in.
     */
    fun onRemove() = act {
        if (!withContext(io) { canInstall() }) {
            _state.update { it.copy(needsInstallPermission = true) }
            return@act null
        }
        val refusal = withContext(io) { flow.askedToUninstall(packageName) }
        if (refusal == null) {
            _state.update { it.copy(awaitingUninstall = true, stillInstalled = false) }
            _events.send(RunEvent.LaunchUninstall(packageName))
        }
        refusal
    }

    /** "Report how it works" on Done after putting the original back; nothing before then. */
    fun onReport() {
        val record = _state.value.restoredRecord ?: return
        keepForReport(record)
        viewModelScope.launch { _events.send(RunEvent.OpenReport(packageName)) }
    }

    /** Android's uninstall dialog answered (or couldn't open). */
    fun onUninstallReturned() {
        _state.update { it.copy(awaitingUninstall = false) }
        viewModelScope.launch { read(afterUninstall = true) }
    }

    fun onStop() = act {
        val stopped = withContext(io) { flow.stop(packageName) }
        if (stopped) {
            forgetJob(packageName)
            _events.send(RunEvent.Closed)
            null
        } else {
            "${_state.value.label} can't be stopped at this step."
        }
    }

    /** "Try again" after a failed job, or "Continue" when none is running. */
    fun onRetry() = act {
        if (startJob(packageName, _state.value.label)) null else PATCHING_NOT_STARTED
    }

    /** "Install the patched copy", or for a Refresh "Install the update". */
    fun onInstallPatched() = act { install(original = false) }

    /** "Put the original back" while putting it back. */
    fun onInstallOriginal() = act { install(original = true) }

    /** "Put the original back" while the patched copy waits to install: the original is saved, so it goes back in. */
    fun onPutBack() = act {
        withContext(io) { flow.startRestore(packageName) } ?: run {
            read()
            install(original = true)
        }
    }

    /**
     * Runs one of the customer's steps, unless one is running or Android's uninstall dialog is up.
     * [step] returns why it didn't happen, or null; the phone is read again after it. A step that
     * throws (a record write on a full disk, say) says so instead of crashing; the read after it shows where the swap is.
     */
    private fun act(step: suspend () -> String?) {
        val current = _state.value
        if (current.busy || current.awaitingUninstall) return
        _state.update { it.copy(busy = true, message = null, needsInstallPermission = false) }
        viewModelScope.launch {
            val message = try {
                step()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                STEP_FAILED
            } finally {
                _state.update { it.copy(busy = false) }
            }
            _state.update { it.copy(message = message) }
            read()
        }
    }

    private suspend fun install(original: Boolean): String? {
        if (!withContext(io) { canInstall() }) {
            _state.update { it.copy(needsInstallPermission = true) }
            return null
        }
        val outcome = if (original) flow.installOriginal(packageName) else flow.installPatched(packageName)
        return (outcome as? InstallOutcome.Failure)?.message
    }

    private suspend fun read(afterUninstall: Boolean = false) = reading.withLock {
        // Back from "Open settings" (a resume reads): the banner goes once installs are allowed.
        val permitted = _state.value.needsInstallPermission && withContext(io) { canInstall() }
        val (record, phase, keyKnown) = withContext(io) {
            // Known before and after the phase: a key cached or cleared meanwhile leaves it unsure.
            val keyBefore = flow.keyKnown()
            val record = flow.record(packageName)
            val phase = record?.let { flow.phase(packageName) }
            // Refused while an install of the app still reports, or failing to write: the next read finishes it.
            if (phase == GenericPhase.Patched || phase == GenericPhase.Restored) {
                try {
                    flow.finish(packageName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // The phone already shows the swap done.
                }
            }
            Triple(record, phase, keyBefore && flow.keyKnown())
        }
        // The Library's rule (GenericLibrarySource): Attention without the key isn't shown.
        val unchecked = phase is GenericPhase.Attention && !keyKnown
        _state.update { current ->
            val removing = phase is GenericPhase.RemoveOriginal || phase is GenericPhase.RemovePatched
            // Asked before this screen opened (the store was closed meanwhile), and still here.
            val askedBefore = (phase as? GenericPhase.RemoveOriginal)?.askedBefore == true ||
                (phase as? GenericPhase.RemovePatched)?.askedBefore == true
            val done = current.done || phase == GenericPhase.Patched || phase == GenericPhase.Restored
            current.copy(
                loading = false,
                label = record?.label ?: current.label,
                intent = record?.intent ?: current.intent.takeIf { done },
                // Written in enum order by the confirm flow; a record without names is an old (ads-only) patch.
                capabilities = record?.let { it.capabilities?.let(::capabilitiesOf) ?: setOf(Capability.ADS) }
                    ?: current.capabilities,
                phase = phase.takeUnless { unchecked },
                originalSaved = record?.original?.isNotEmpty() == true,
                stillInstalled = removing && (afterUninstall || current.stillInstalled || (current.loading && askedBefore)),
                // Only ever cleared here; a step sets it from its own check.
                needsInstallPermission = current.needsInstallPermission && !permitted,
                done = done,
                unchecked = unchecked,
                restoredRecord = current.restoredRecord ?: record.takeIf { phase == GenericPhase.Restored },
            )
        }
    }

    private companion object {
        const val STEP_FAILED = "That step didn't work. Try again."
    }
}
