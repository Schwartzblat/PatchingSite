package site.androidmod.store.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import site.androidmod.store.StoreApp
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.NotEnoughSpaceException
import site.androidmod.store.data.StoreUpdateRepository
import site.androidmod.store.download.DownloadService
import site.androidmod.store.install.InstallOutcome
import site.androidmod.store.ui.notEnoughSpaceMessage
import java.io.File
import java.io.IOException

sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data object Checking : UpdatePhase

    /** [total] is -1 when the server sent no Content-Length. */
    data class Downloading(val bytesRead: Long, val total: Long) : UpdatePhase
    data object Installing : UpdatePhase
}

data class UpdateState(
    val installed: InstalledBuild,
    /** Null until the first check lands. Kept across a failed re-check. */
    val check: UpdateCheck? = null,
    val phase: UpdatePhase = UpdatePhase.Idle,
    /**
     * A failed version check. Only Settings shows it: the launch-time check runs
     * unasked, and a banner saying it failed would be noise on every flaky network.
     */
    val checkError: String? = null,
    /** A failed update, shown wherever the Update button is. */
    val error: String? = null,
    val needsInstallPermission: Boolean = false,
) {
    val available: UpdateCheck.Available? get() = check as? UpdateCheck.Available
}

/**
 * The store updating itself: check the published build on launch, and on request
 * download it and hand it to the same installers every patched app goes through.
 *
 * Created once in AppNavigation, like CatalogViewModel, so the Apps-tab banner and
 * the Settings section read one state - an update started from either shows its
 * progress in both.
 */
class UpdateViewModel(
    private val repository: StoreUpdateRepository = StoreApp.instance.container.storeUpdates,
    installed: InstalledBuild = StoreApp.instance.container.installedBuild(),
    private val directory: File = DownloadService.directoryFor(StoreApp.instance),
    private val installer: SelfInstaller = StoreApp.instance.container.selfInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateState(installed))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    init {
        check()
    }

    fun check() {
        // Also refuses while an update runs: a check landing mid-install could flip
        // the banner to "up to date" underneath its own progress bar.
        if (_state.value.phase != UpdatePhase.Idle) return
        _state.update { it.copy(phase = UpdatePhase.Checking, checkError = null) }
        viewModelScope.launch {
            try {
                val published = repository.info()
                _state.update { it.copy(phase = UpdatePhase.Idle, check = UpdateCheck.of(published, it.installed)) }
            } catch (error: ApiException) {
                _state.update { it.copy(phase = UpdatePhase.Idle, checkError = error.message) }
            }
        }
    }

    fun update() {
        val available = _state.value.available ?: return
        if (_state.value.phase != UpdatePhase.Idle) return
        // The phase moves before the coroutine starts, so a second tap on Update
        // is refused by the guard above rather than starting a second download.
        _state.update {
            it.copy(
                phase = UpdatePhase.Downloading(0, available.size),
                error = null,
                needsInstallPermission = false,
            )
        }
        viewModelScope.launch {
            if (!installer.canInstall()) {
                _state.update { it.copy(phase = UpdatePhase.Idle, needsInstallPermission = true) }
                return@launch
            }

            // A successful self-update normally never returns here: Android kills
            // this process to replace its APK. What does return is a failure, or a
            // success the system reported before the kill.
            val error = try {
                val outcome = StoreSelfUpdate.downloadAndInstall(
                    repository,
                    installer,
                    directory,
                    onProgress = { read, total ->
                        _state.update { it.copy(phase = UpdatePhase.Downloading(read, total)) }
                    },
                    onInstalling = { _state.update { it.copy(phase = UpdatePhase.Installing) } },
                )
                (outcome as? InstallOutcome.Failure)?.message
            } catch (error: ApiException) {
                // A transfer that keeps dying mid-stream arrives here too: ApiClient
                // resumes it a few times, then reports it as an ApiException.
                error.message ?: "The update could not be downloaded."
            } catch (error: NotEnoughSpaceException) {
                // Before the IOException arm, which it extends: "interrupted" would
                // send the user off to check a connection that is fine.
                notEnoughSpaceMessage(error)
            } catch (error: IOException) {
                // A failed write to local storage other than a full disk.
                "The download was interrupted. Please try again."
            }
            _state.update { it.copy(phase = UpdatePhase.Idle, error = error) }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null, needsInstallPermission = false) }
    }
}
