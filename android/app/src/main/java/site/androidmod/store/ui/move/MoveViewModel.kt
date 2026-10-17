package site.androidmod.store.ui.move

import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.patcher.migrate.SpareCopy
import com.patcher.migrate.Status
import com.patcher.migrate.Step
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.androidmod.store.Container
import site.androidmod.store.StoreApp
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.BuildVersion
import site.androidmod.store.data.isAuth
import site.androidmod.store.download.DownloadKey
import site.androidmod.store.download.DownloadRegistry
import site.androidmod.store.move.MoveAcks
import site.androidmod.store.move.MoveGuide
import site.androidmod.store.move.SaveProblem
import site.androidmod.store.move.WHATSAPP_PACKAGE
import site.androidmod.store.move.moveGuide
import site.androidmod.store.update.StoreSelfUpdate

/** A step that stopped, in the guide's words, with the engine's for the Details fold. */
data class MoveFailure(val message: String, val details: List<String>)

data class MoveState(
    /** Null until the first read; nothing is shown about a move before then. */
    val status: Status? = null,
    val ours: Boolean = false,
    /** The backup left ours: a return. See MoveRepository.parkedFromOurs. */
    val parkedFromOurs: Boolean = false,
    /** The installed WhatsApp is a previous build; see MoveRepository.isPreviousBuild. */
    val previous: Boolean = false,
    /** The backup left a previous build; see MoveRepository.parkedFromPrevious. */
    val parkedFromPrevious: Boolean = false,
    /** The user chose to go back from a previous build; see SettingsStore.whatsAppPreviousGoesBack. */
    val previousGoesBack: Boolean = false,
    val moveActive: Boolean = false,
    /** The stored direction of a move under way, which it keeps; see moveDirection. */
    val toOfficialHint: Boolean = false,
    /** When the user tapped Start; see SettingsStore.whatsAppMoveStartedAt. */
    val startedAt: Long = 0,
    /** See SettingsStore.whatsAppMoveSeenInstalledAt. */
    val seenInstalledAt: Long = 0,
    /** Why the backup could not be saved, for steps 2-4; null when it was, or nothing was tried. */
    val saveProblem: SaveProblem? = null,
    /** What finishing would remove. Read only at the engine's DONE, since it walks the folders. */
    val spareCopies: List<SpareCopy> = emptyList(),
    /** WhatsApp's builds, newest first. */
    val versions: List<BuildVersion> = emptyList(),
    val versionsError: String? = null,
    /** WhatsApp is patched on the phone: the server's `latest`, not its builds, is what is newest. */
    val onDevice: Boolean = false,
    /** The server's latest WhatsApp version (on-device only); null until it has loaded. */
    val latestVersion: String? = null,
    /** The latest check was refused for want of a subscription (402). */
    val latestNeedsSubscription: Boolean = false,
    /**
     * On-device, the user's signing key is neither cached nor fetchable right now, so the
     * user's own build can't be told from the regular one; see hideMoveWhileKeyUnknown.
     */
    val keyUnknown: Boolean = false,
    val acks: MoveAcks = MoveAcks(),
    /** What a blocking step is doing right now ("Saving your backup…"), or null. */
    val busy: String? = null,
    val failure: MoveFailure? = null,
    val readAt: Long = 0,
)

/** One read of the phone, taken off the main thread and applied on it. */
private data class DeviceRead(
    val onDevice: Boolean,
    val keyKnown: Boolean,
    val status: Status,
    val ours: Boolean,
    val parkedFromOurs: Boolean,
    val previous: Boolean,
    val parkedFromPrevious: Boolean,
    val previousGoesBack: Boolean,
    val moveActive: Boolean,
    val toOfficialHint: Boolean,
    val seenInstalledAt: Long,
    val saveProblem: SaveProblem?,
    val spareCopies: List<SpareCopy>,
)

sealed interface MoveEvent {
    /** The backup is parked; open Android's uninstall dialog, which needs the Activity. */
    data object LaunchUninstall : MoveEvent
}

/**
 * The WhatsApp chat move, for every screen that shows a part of it: the WhatsApp
 * page's card, the Apps banner, the Library's warnings and the guide itself.
 * Activity-scoped (created in AppNavigation) so they all read one answer.
 *
 * It decides nothing itself; [moveGuide] does. It reads the device, holds what the
 * user has acknowledged, and runs the actions that are not installs.
 */
class MoveViewModel(
    private val container: Container = StoreApp.instance.container,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ViewModel() {

    private val _state = MutableStateFlow(MoveState())
    val state: StateFlow<MoveState> = _state.asStateFlow()

    private val _events = Channel<MoveEvent>(Channel.BUFFERED)
    val events: Flow<MoveEvent> = _events.receiveAsFlow()

    /** The build the guide downloads and installs: the newest one. */
    val buildKey: DownloadKey?
        get() = _state.value.versions.firstOrNull()?.let { DownloadKey(WHATSAPP_PACKAGE, it.version) }

    val guide: StateFlow<MoveGuide> = combine(_state, DownloadRegistry.downloads) { state, _ ->
        val status = state.status ?: return@combine MoveGuide.Empty
        val guide = moveGuide(
            status = status,
            ours = state.ours,
            parkedFromOurs = state.parkedFromOurs,
            moveActive = state.moveActive,
            toOfficialHint = state.toOfficialHint,
            newestVersion = guideNewestVersion(state.onDevice, state.versions, state.latestVersion),
            downloaded = downloadedKey(state) != null,
            now = state.readAt,
            startedAt = state.startedAt,
            acks = state.acks,
            seenInstalledAt = state.seenInstalledAt,
            previous = state.previous,
            parkedFromPrevious = state.parkedFromPrevious,
            previousGoesBack = state.previousGoesBack,
        )
        val shown = hideMoveWhileKeyUnknown(guide, state.onDevice, keyKnown = !state.keyUnknown, previous = state.previous)
        moveIntoOursOnOldAndroid(shown, state.onDevice, sdkInt)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MoveGuide.Empty)

    private var refreshJob: Job? = null
    private var selfUpdateTried = false
    private var keyJob: Job? = null
    private var lastKeyFailureAt = 0L

    init {
        refresh()
    }

    /**
     * Re-reads the device. Every screen showing any of this calls it on resume: the
     * user changes the state in other apps - WhatsApp, Settings, the uninstaller - and
     * comes back.
     *
     * First it lets the repository keep track (MoveRepository.track), as every open
     * of the store does. That is cheap almost always: a presence check and a listing.
     * The exception is the first read after the backup step 2 accepts, which copies
     * it - megabytes, once - so that removing WhatsApp from Settings afterwards no
     * longer loses the chats.
     */
    fun refresh(retryKey: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val oldBackupChosen = _state.value.acks.oldBackup
            val read = withContext(Dispatchers.IO) {
                // Which build is the user's own depends on the key; unknown hides only the move's offer.
                val onDevice = WHATSAPP_PACKAGE in container.settings.onDevicePackages
                val keyKnown = !onDevice || container.signingKeys.cached() != null
                try {
                    container.move.track(oldBackupChosen)
                } catch (error: Throwable) {
                    // The read below still shows the truth; only the copy or the proof waits.
                    Log.w(TAG, "Keeping track of the chat move failed", error)
                }
                val status = container.move.status()
                DeviceRead(
                    onDevice = onDevice,
                    keyKnown = keyKnown,
                    status = status,
                    ours = container.move.isOurs(status.facts),
                    parkedFromOurs = container.move.parkedFromOurs(status.facts),
                    previous = container.move.isPreviousBuild(status.facts),
                    parkedFromPrevious = container.move.parkedFromPrevious(status.facts),
                    previousGoesBack = container.move.previousGoesBack,
                    moveActive = container.move.moveActive,
                    toOfficialHint = container.move.moveToOfficial,
                    seenInstalledAt = container.move.seenInstalledAt,
                    saveProblem = container.move.saveProblem,
                    spareCopies = if (status.step == Step.DONE) container.move.spareCopies() else emptyList(),
                )
            }
            _state.value = _state.value.copy(
                onDevice = read.onDevice, keyUnknown = !read.keyKnown,
                status = read.status, ours = read.ours, parkedFromOurs = read.parkedFromOurs,
                previous = read.previous, parkedFromPrevious = read.parkedFromPrevious,
                previousGoesBack = read.previousGoesBack,
                moveActive = read.moveActive, toOfficialHint = read.toOfficialHint,
                seenInstalledAt = read.seenInstalledAt, saveProblem = read.saveProblem,
                spareCopies = read.spareCopies,
                startedAt = container.move.startedAt, readAt = System.currentTimeMillis(),
            )
            if (read.onDevice && !read.keyKnown) fetchSigningKey(retryKey)
            val current = _state.value
            if (if (current.onDevice) current.latestVersion == null else current.versions.isEmpty()) loadVersions()
        }
    }

    /**
     * On-device with no key cached: fetches it in a job of its own, so a slow or failing
     * request never holds back a phone read (R9 hides the offer meanwhile), and reads again
     * once it arrives. One fetch at a time, and not again within [KEY_RETRY_MILLIS] of a
     * failure unless the user asked ([force]): the server limits key requests.
     */
    private fun fetchSigningKey(force: Boolean) {
        if (keyJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force && now - lastKeyFailureAt < KEY_RETRY_MILLIS) return
        keyJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                container.signingKeys.get()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The key itself is never in an exception; the message may be the server's.
                Log.w(TAG, "Fetching the signing key failed", error)
                lastKeyFailureAt = System.currentTimeMillis()
                if (error is ApiException && error.isAuth) container.auth.expireSession()
                return@launch
            }
            refresh()
        }
    }

    private suspend fun loadVersions() {
        if (_state.value.onDevice) {
            loadLatest()
            return
        }
        try {
            val versions = container.catalog.versions(WHATSAPP_PACKAGE)
            _state.value = _state.value.copy(versions = versions, versionsError = null)
        } catch (error: ApiException) {
            if (error.isAuth) container.auth.expireSession()
            _state.value = _state.value.copy(versionsError = error.message)
        }
    }

    /** On-device: the newest WhatsApp the server can patch, in place of its list of builds. */
    private suspend fun loadLatest() {
        try {
            val latest = container.patchApi.latest(WHATSAPP_PACKAGE)
            _state.value = _state.value.copy(
                latestVersion = latest.versionName, versionsError = null, latestNeedsSubscription = false,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val moveActive = _state.value.moveActive
            val problem = latestProblem(error, moveActive) ?: throw error
            if (error is ApiException && error.isAuth) container.auth.expireSession()
            _state.value = _state.value.copy(
                versionsError = problem.message, latestNeedsSubscription = problem.needsSubscription,
            )
            if (guideStartsSelfUpdate(problem, moveActive, selfUpdateTried)) {
                // Once per visit: it downloads a whole store, and every resume lands here again.
                // Never mid-move: the guide doesn't replace the store under a move by itself.
                selfUpdateTried = true
                // Its own job: a refresh (every resume) must not cancel a download half way.
                viewModelScope.launch {
                    try {
                        StoreSelfUpdate.run(container)
                    } catch (updateError: CancellationException) {
                        throw updateError
                    } catch (updateError: Exception) {
                        Log.w(TAG, "Store self-update failed", updateError)
                    }
                }
            }
        }
    }

    fun retryVersions() {
        viewModelScope.launch { loadVersions() }
    }

    /**
     * The user is opening the guide. Called on the way in from every entry point,
     * and a no-op once a move is under way, so Continue never resets anything.
     */
    fun markStarted() {
        if (guide.value.inProgress) return
        // A new visit: "All done" belonged to the move that just ended.
        updateAcks { it.copy(finished = null) }
        val now = System.currentTimeMillis()
        container.move.markStarted(now)
        _state.value = _state.value.copy(startedAt = container.move.startedAt)
    }

    /**
     * "Going back to the regular WhatsApp?" with a previous build installed, whose guide
     * otherwise moves into ours: the guide turns to the way back. Stored, because the
     * move's direction is written from it once the backup is saved or parked; false
     * undoes it. Only before anything has moved - after that the move keeps the way it
     * began - and a no-op for any other WhatsApp, so every way into going back can call it.
     */
    fun chooseGoingBack(goingBack: Boolean = true) {
        if (!_state.value.previous || guide.value.inProgress) return
        container.move.previousGoesBack = goingBack
        _state.value = _state.value.copy(previousGoesBack = goingBack)
        // A read already under way took the old value; this one replaces it.
        refresh()
    }

    /** The older backup is accepted, and saved straight away, as a fresh one is on the next read. */
    fun useOldBackup() {
        updateAcks { it.copy(oldBackup = true) }
        refresh()
    }

    fun knowPassword(known: Boolean) = updateAcks { it.copy(password = known) }

    /** "My chats are back", into the engine's record, so a process that dies doesn't ask again. */
    fun confirmChatsBack() {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(failure = null)
            val result = withContext(Dispatchers.IO) {
                try {
                    container.move.confirmChats()
                } catch (error: Throwable) {
                    com.patcher.migrate.Result.failed("The confirmation threw: ${error.message}")
                }
            }
            if (!result.ok) {
                _state.value = _state.value.copy(
                    failure = MoveFailure(
                        "We couldn't note that your chats are back. Try again, or contact us.",
                        listOf(result.summary) + result.details,
                    ),
                )
            }
            refresh()
        }
    }

    fun dismissFailure() {
        _state.value = _state.value.copy(failure = null)
    }

    private fun updateAcks(change: (MoveAcks) -> MoveAcks) {
        _state.value = _state.value.copy(acks = change(_state.value.acks))
    }

    /**
     * Step 4. Parks the backup first when it is still in WhatsApp's folder, and only
     * once that has succeeded asks for the uninstall. A failed park never reaches
     * the uninstaller: that order is the whole point of the step. The park waits for
     * a copy still being made (MoveRepository's OneAtATime), so tapping this right
     * after coming back from WhatsApp shows "Saving your backup…" a little longer.
     */
    fun removeOldWhatsApp() {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { container.move.status() }
            if (status.step == Step.BACK_UP_AND_PARK) {
                if (!status.canPark) {
                    refresh()
                    return@launch
                }
                _state.value = _state.value.copy(busy = "Saving your backup… This can take a few seconds.", failure = null)
                val result = withContext(Dispatchers.IO) {
                    try {
                        container.move.park()
                    } catch (error: Throwable) {
                        com.patcher.migrate.Result.failed("The park threw: ${error.message}")
                    }
                }
                _state.value = _state.value.copy(busy = null)
                if (!result.ok) {
                    _state.value = _state.value.copy(
                        failure = MoveFailure(
                            "Your backup couldn't be saved, so nothing was removed. Try again, or contact us.",
                            listOf(result.summary) + result.details,
                        ),
                    )
                    refresh()
                    return@launch
                }
            }
            _events.trySend(MoveEvent.LaunchUninstall)
            refresh()
        }
    }

    /**
     * Going back, step 4's fallback, for when WhatsAppInstallWatcher did not run: the
     * store's process was dead while the Play Store installed. The same rule as the
     * watcher, so this cannot put a backup in front of a WhatsApp that would refuse it.
     */
    fun putBackNow() {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "Putting your backup back…", failure = null)
            val result = withContext(Dispatchers.IO) {
                try {
                    container.move.putBackAfterInstall()
                } catch (error: Throwable) {
                    com.patcher.migrate.Result.failed("The put-back threw: ${error.message}")
                }
            }
            _state.value = _state.value.copy(busy = null)
            if (result != null && !result.ok) {
                _state.value = _state.value.copy(
                    failure = MoveFailure(
                        "Your backup couldn't be put back. Don't open WhatsApp. Your backup is still on this " +
                            "phone. Contact us and we'll help you finish.",
                        listOf(result.summary) + result.details,
                    ),
                )
            }
            refresh()
        }
    }

    /**
     * Steps 2-4's "Cancel the move": the saved copy and the record go, and WhatsApp
     * keeps its chats. The engine refuses unless WhatsApp still holds the same backup.
     */
    fun cancelMove() {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "Cancelling the move…", failure = null)
            val result = withContext(Dispatchers.IO) {
                try {
                    container.move.cancelMove()
                } catch (error: Throwable) {
                    com.patcher.migrate.Result.failed("The cancel threw: ${error.message}")
                }
            }
            _state.value = _state.value.copy(busy = null)
            if (result.ok) {
                // A new Start begins from nothing: the older-backup choice belonged to this move.
                updateAcks { MoveAcks() }
                // Cancel clears the Start. With no Start any backup under an hour reads as
                // fresh and nothing is saved, so a move continued in the still-open guide
                // would be unprotected; a new Start makes it ask for a fresh backup and save it.
                container.move.markStarted(System.currentTimeMillis())
                _state.value = _state.value.copy(startedAt = container.move.startedAt)
            } else {
                _state.value = _state.value.copy(
                    failure = MoveFailure(
                        "The move couldn't be cancelled. Your chats are still in your WhatsApp. Try again, " +
                            "or contact us.",
                        listOf(result.summary) + result.details,
                    ),
                )
            }
            refresh()
        }
    }

    /** Finish up: removes every spare copy, which also ends the move. Only reachable at DONE. */
    fun removeSpareCopy() {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            // Read before the refresh below: with the manifest gone, the phone alone
            // reads as the start of the opposite move.
            val direction = guide.value.direction
            _state.value = _state.value.copy(busy = "Removing the spare copy…", failure = null)
            val result = withContext(Dispatchers.IO) {
                try {
                    container.move.finish()
                } catch (error: Throwable) {
                    com.patcher.migrate.Result.failed("The delete threw: ${error.message}")
                }
            }
            _state.value = _state.value.copy(busy = null)
            if (result.ok) {
                updateAcks { it.copy(finished = direction) }
            } else {
                _state.value = _state.value.copy(
                    failure = MoveFailure(
                        "The spare copy couldn't be removed. Your chats in WhatsApp are not affected. " +
                            "Try again, or contact us.",
                        listOf(result.summary) + result.details,
                    ),
                )
            }
            refresh()
        }
    }

    /** The support email's move section; the device context is added by the caller. */
    fun supportSummary(): String {
        val status = _state.value.status ?: return "Guide: not read yet\n"
        val failure = _state.value.failure
        return container.move.supportSummary(status, guide.value) +
            (failure?.let { "Last failure: ${it.details.joinToString(" / ")}\n" } ?: "")
    }

    /**
     * The WhatsApp build on disk that step 5 installs. The newest when the list has
     * loaded; otherwise any finished WhatsApp download, so an offline list read
     * cannot hide a build that is already here.
     */
    fun downloadedKey(): DownloadKey? = downloadedKey(_state.value)

    private fun downloadedKey(state: MoveState): DownloadKey? {
        val newest = state.versions.firstOrNull()?.let { DownloadKey(WHATSAPP_PACKAGE, it.version) }
        if (newest != null) return newest.takeIf { DownloadRegistry.fileFor(it) != null }
        return DownloadRegistry.downloads.value.keys.firstOrNull {
            it.packageName == WHATSAPP_PACKAGE && DownloadRegistry.fileFor(it) != null
        }
    }

    private companion object {
        const val TAG = "PatchedStore"
        const val KEY_RETRY_MILLIS = 60_000L
    }
}
