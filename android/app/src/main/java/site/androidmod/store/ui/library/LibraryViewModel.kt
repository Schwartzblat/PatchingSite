package site.androidmod.store.ui.library

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.androidmod.store.StoreApp
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.isAuth
import site.androidmod.store.download.DownloadKey
import site.androidmod.store.download.DownloadRegistry
import site.androidmod.store.install.InstallErrors
import site.androidmod.store.install.InstallFailure
import site.androidmod.store.install.InstallOutcome
import site.androidmod.store.install.InstalledApp
import site.androidmod.store.install.VersionOrder
import site.androidmod.store.install.Versions
import site.androidmod.store.move.InstallGate
import site.androidmod.store.move.WHATSAPP_PACKAGE
import site.androidmod.store.data.needsSubscription
import site.androidmod.store.patch.PATCH_MIN_SDK
import site.androidmod.store.patch.PatchFailure
import site.androidmod.store.patch.PatchMode
import site.androidmod.store.patch.PatchService
import site.androidmod.store.patch.StoreUpdateRequired
import site.androidmod.store.patch.UpdateOffer
import site.androidmod.store.patch.isValidUpdateRequest
import site.androidmod.store.patch.message
import site.androidmod.store.patch.readPatchInfo
import site.androidmod.store.patch.updateOffer
import site.androidmod.store.ui.NavRequest
import site.androidmod.store.ui.NavigationRequests
import site.androidmod.store.update.StoreSelfUpdate
import kotlinx.coroutines.CancellationException
import java.io.File

data class LibraryEntry(
    val packageName: String,
    val name: String,
    val installed: InstalledApp?,
    val latest: String?,
    val order: VersionOrder,
    /** Built on the phone: [latest] comes from the patch API and [offer] decides the button. */
    val onDevice: Boolean = false,
    val offer: UpdateOffer = UpdateOffer.None,
) {
    val updateAvailable: Boolean
        get() = if (onDevice) {
            offer is UpdateOffer.NewVersion || offer is UpdateOffer.PatchUpdate
        } else installed != null && order == VersionOrder.NEWER
}

/** The one action an entry's card offers besides Install/Uninstall. */
enum class LibraryAction { NONE, UPDATE, MOVE }

internal fun libraryAction(entry: LibraryEntry): LibraryAction {
    if (!entry.onDevice) return LibraryAction.NONE
    return when (entry.offer) {
        UpdateOffer.None -> LibraryAction.NONE
        is UpdateOffer.NewVersion, is UpdateOffer.PatchUpdate -> LibraryAction.UPDATE
        UpdateOffer.NeedsMove -> LibraryAction.MOVE
    }
}

internal fun libraryActionLabel(action: LibraryAction): String? = when (action) {
    LibraryAction.NONE -> null
    LibraryAction.UPDATE -> "Update"
    LibraryAction.MOVE -> "Move to the new update system"
}

internal fun upToDateMessage(name: String) = "$name is up to date"

/**
 * What an outside update request (a notification, the patched app's own button) says when
 * there is nothing to start. On Android 11 and 12 nothing can be patched, so "up to date"
 * would be false whatever the server has.
 */
internal fun noUpdateMessage(name: String, sdkInt: Int): String =
    if (sdkInt < PATCH_MIN_SDK) PatchFailure.ANDROID_TOO_OLD.message() else upToDateMessage(name)

private const val SUBSCRIPTION_NEEDED = "You need a subscription to update."

data class LibraryState(
    val entries: List<LibraryEntry> = emptyList(),
    val loading: Boolean = true,
    /**
     * A failed install, and its "Uninstall and retry" affordance. Owned by
     * [LibraryViewModel.install]: a refresh must never clear an install failure it
     * did not cause, because refresh() now runs on every ON_RESUME - including the
     * resume that happens when the user dismisses the system install dialog, which
     * is precisely when the signature-conflict banner has just been raised.
     */
    val error: String? = null,
    val installing: DownloadKey? = null,
    val conflictWith: String? = null,
    val needsInstallPermission: Boolean = false,
    /** A failed catalogue read. Owned by [LibraryViewModel.refresh], which clears it. */
    val loadError: String? = null,
    /** WhatsApp's parked backup is being put back ahead of its install; see MoveRepository.beforeInstall. */
    val movingBackup: Boolean = false,
)

/**
 * Whether a raised signature conflict is still worth showing after a refresh.
 *
 * A conflict says "a build signed by someone else is installed; uninstall it
 * first". That is actionable only while the offending copy is actually there, so
 * the rule is: drop it once the package is observed NOT installed - which is
 * exactly the uninstall having succeeded - and keep it otherwise. A refresh merely
 * having run is not evidence of anything.
 *
 * An unknown package (not in the catalogue at all) keeps the conflict, because
 * "not listed" is not the same observation as "not installed", and losing the
 * recovery affordance is the failure mode worth avoiding here.
 *
 * Top-level and pure so the rule can be tested without an Android runtime.
 */
internal fun conflictAfterRefresh(current: String?, entries: List<LibraryEntry>): String? {
    val conflict = current ?: return null
    val entry = entries.firstOrNull { it.packageName == conflict } ?: return conflict
    return conflict.takeIf { entry.installed != null }
}

/**
 * The toast raised when an install finishes: the verdict, and on failure why.
 *
 * Two lines at most, because Android 12+ cuts a text toast off after the second:
 * the verdict with a short reason, then the installer's own code. When the failure
 * is unrecognised there is no short reason, so the second line carries the message
 * instead - otherwise the toast would say it failed without saying why. The full
 * explanation is the error banner's job, not this.
 */
internal fun installToast(name: String, version: String, outcome: InstallOutcome): String = when (outcome) {
    is InstallOutcome.Success -> "Installed $name $version"
    is InstallOutcome.Failure -> {
        val summary = InstallErrors.summarize(outcome.reason)
        val headline = if (summary != null) "Install failed: $summary" else "Install failed"
        val detail = outcome.code ?: outcome.message.takeIf { summary == null }
        listOfNotNull(headline, detail).joinToString("\n")
    }
}

class LibraryViewModel(
    private val container: site.androidmod.store.Container = StoreApp.instance.container,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /**
     * One toast text per finished install, collected by AppNavigation.
     *
     * A Channel, not a LibraryState field: a toast is an event, and as state it
     * would fire again on every recreation of the activity. Buffered, so a result
     * that lands while nothing is collecting - the activity mid-recreation - waits
     * for the next collector rather than being dropped.
     */
    private val _installResults = Channel<String>(Channel.BUFFERED)
    val installResults: Flow<String> = _installResults.receiveAsFlow()

    /** The in-flight reconcile, so a superseded one can be cancelled. */
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    /**
     * Re-read what is installed and what the newest build is.
     *
     * Runs from init, after a successful install, and on every ON_RESUME of the
     * Library screen - the last of those because uninstall() fires ACTION_DELETE,
     * which reports no result: without it the card still reads "Installed 42.0 ·
     * up to date" straight after its own Uninstall button did the job.
     *
     * Those three callers overlap: coming back from the system uninstaller while
     * an install-triggered refresh is still in flight would let the older answer
     * land last and win. Cancelling the superseded job is the same pattern
     * CatalogViewModel.loadVersions uses, and the catch stays scoped to
     * ApiException so the CancellationException still propagates.
     *
     * It updates the catalogue view and NOTHING else. Assigning a fresh
     * LibraryState here - which is what it used to do - reset error, conflictWith,
     * installing and needsInstallPermission to their defaults, which was harmless
     * while refresh() only ran from init and after a SUCCESSFUL install, and became
     * a real regression the moment it started running on every resume.
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, loadError = null)
            try {
                val apps = container.catalog.apps()
                // Fetched at most once per refresh, and only when an on-device app is installed.
                var userCert: String? = null
                var userCertKnown = false
                val entries = apps.map { app ->
                    val installed = container.installedApps.find(app.packageName)
                    if (app.onDevice) {
                        // A refresh is also a chance to learn of an update (spec 7); the in-app
                        // state only, notifications are the worker's.
                        if (installed != null && !userCertKnown) {
                            userCert = userCertSha256()
                            userCertKnown = true
                        }
                        return@map onDeviceEntry(app.packageName, app.name, installed, userCert)
                    }
                    // Scoped to ApiException, not runCatching: a broad catch here
                    // swallows the CancellationException of a SUPERSEDED refresh, so
                    // the losing job runs on to completion and writes its (now
                    // version-less) answer anyway.
                    val latest = try {
                        container.catalog.versions(app.packageName).firstOrNull()?.version
                    } catch (error: ApiException) {
                        null
                    }
                    LibraryEntry(
                        packageName = app.packageName,
                        name = app.name,
                        installed = installed,
                        latest = latest,
                        order = if (installed != null && latest != null) {
                            Versions.compare(latest, installed.versionName)
                        } else VersionOrder.UNKNOWN,
                    )
                }
                val conflict = conflictAfterRefresh(_state.value.conflictWith, entries)
                _state.value = _state.value.copy(
                    entries = entries,
                    loading = false,
                    loadError = null,
                    conflictWith = conflict,
                    // The message that went with a resolved conflict told the user to
                    // uninstall the other copy. Once they have, it is stale too.
                    error = if (_state.value.conflictWith != null && conflict == null) {
                        null
                    } else _state.value.error,
                )
            } catch (error: ApiException) {
                // A 401 here is an expired session, not a broken screen: hand it to
                // the one place that can put the app back on the login form.
                if (error.isAuth) container.auth.expireSession()
                _state.value = _state.value.copy(loading = false, loadError = error.message)
            }
        }
    }

    /** The user's signing certificate: cached, else fetched; null when it cannot be had (their key is then unknown). */
    private suspend fun userCertSha256(): String? =
        container.signingKeys.cached()?.certSha256 ?: try {
            withContext(Dispatchers.IO) { container.signingKeys.get().certSha256 }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }

    private suspend fun onDeviceEntry(
        pkg: String,
        name: String,
        installed: InstalledApp?,
        userCert: String?,
    ): LibraryEntry {
        val latest = try {
            container.patchApi.latest(pkg)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException) {
            if (error.isAuth) container.auth.expireSession()
            null
        } catch (error: StoreUpdateRequired) {
            null
        }
        val offer = if (latest == null || installed == null) UpdateOffer.None else {
            val patchInfo = withContext(Dispatchers.IO) { installed.sourceDir?.let { readPatchInfo(File(it)) } }
            updateOffer(installed, patchInfo, latest, userCert, container.move.ownSignature, Build.VERSION.SDK_INT)
        }
        return LibraryEntry(
            packageName = pkg,
            name = name,
            installed = installed,
            latest = latest?.versionName,
            order = if (installed != null && latest != null) {
                Versions.compare(latest.versionName, installed.versionName)
            } else VersionOrder.UNKNOWN,
            onDevice = true,
            offer = offer,
        )
    }

    /** Begins the on-device update of [pkg]; progress comes from PatchRegistry. */
    fun startUpdate(pkg: String, name: String?) {
        PatchService.start(StoreApp.instance, pkg, PatchMode.UPDATE, name)
    }

    /**
     * An update asked for from outside the Library (a notification, or the patched app's own
     * update button): looks at what is newest right now, then starts the update, opens the
     * move guide, or says there is nothing to do. Said as a toast, like install results.
     */
    fun handleUpdate(pkg: String) {
        val onDevice = container.settings.onDevicePackages
        if (!isValidUpdateRequest(pkg, onDevice) { container.installedApps.find(it) != null }) return
        viewModelScope.launch {
            val name = _state.value.entries.firstOrNull { it.packageName == pkg }?.name
                ?: container.appNames[pkg]
                ?: if (pkg == WHATSAPP_PACKAGE) "WhatsApp" else pkg
            val latest = try {
                container.patchApi.latest(pkg)
            } catch (error: CancellationException) {
                throw error
            } catch (error: StoreUpdateRequired) {
                // Its own job: leaving this screen must not cancel a store download half way.
                viewModelScope.launch {
                    try {
                        StoreSelfUpdate.run(container)
                    } catch (updateError: CancellationException) {
                        throw updateError
                    } catch (updateError: Exception) {
                        android.util.Log.w("PatchedStore", "Store self-update failed", updateError)
                    }
                }
                return@launch
            } catch (error: ApiException) {
                if (error.isAuth) container.auth.expireSession()
                _installResults.trySend(if (error.needsSubscription) SUBSCRIPTION_NEEDED else error.message ?: "Could not check for updates")
                return@launch
            } catch (error: Exception) {
                _installResults.trySend("Could not check for updates")
                return@launch
            }
            val installed = container.installedApps.find(pkg) ?: return@launch
            val patchInfo = withContext(Dispatchers.IO) { installed.sourceDir?.let { readPatchInfo(File(it)) } }
            val offer = updateOffer(
                installed, patchInfo, latest, userCertSha256(), container.move.ownSignature, Build.VERSION.SDK_INT,
            )
            when (offer) {
                UpdateOffer.None -> _installResults.trySend(noUpdateMessage(name, Build.VERSION.SDK_INT))
                is UpdateOffer.NewVersion, is UpdateOffer.PatchUpdate -> startUpdate(pkg, name)
                UpdateOffer.NeedsMove -> NavigationRequests.request(NavRequest.Move)
            }
            refresh()
        }
    }

    fun install(key: DownloadKey) {
        val file = DownloadRegistry.fileFor(key) ?: return
        viewModelScope.launch {
            // needsInstallPermission is reset on EVERY attempt. Otherwise, once a user
            // has been told to grant the permission and has done so, a later failure
            // for an unrelated reason renders both banners at once - telling them to
            // grant a permission they already granted.
            _state.value = _state.value.copy(
                installing = key, error = null, conflictWith = null, needsInstallPermission = false,
            )

            // Resolved off the main thread: installer() reads rootInstaller.isAvailable,
            // which shells out to `su`. A first root grant can block for seconds, and
            // viewModelScope runs on Main.
            val installer = withContext(Dispatchers.IO) { container.installer() }

            // Gate on the installer that will actually run, not on the preference:
            // silent install with no working root falls back to the session installer,
            // which DOES consult the appop. `installing` is cleared here as well, or
            // the button sticks on "Installing..." for an attempt that never started.
            if (installer === container.sessionInstaller && !container.sessionInstaller.canInstall()) {
                _state.value = _state.value.copy(installing = null, needsInstallPermission = true)
                return@launch
            }

            // Here, after the permission gate, so a backup is only ever put back when
            // the install can actually follow. Every store install of WhatsApp comes
            // through this one function, which is what makes it the right place: a
            // parked backup must go back before WhatsApp is installed, whichever
            // screen's button was tapped.
            if (key.packageName == WHATSAPP_PACKAGE) {
                val gate = withContext(Dispatchers.IO) {
                    // update {}, not a read-then-write: this runs on the IO thread
                    // while the main thread may be writing the same state.
                    container.move.beforeInstall(file, key.version) {
                        _state.update { it.copy(movingBackup = true) }
                    }
                }
                _state.value = _state.value.copy(movingBackup = false)
                if (gate is InstallGate.Refused) {
                    _state.value = _state.value.copy(installing = null, error = gate.message)
                    return@launch
                }
            }

            val outcome = installer.install(file)
            // Announced for both installers and from whichever screen started it.
            // Not for the permission gate above: that attempt never started, and its
            // banner already says what to do.
            val name = _state.value.entries.firstOrNull { it.packageName == key.packageName }?.name
                ?: key.packageName
            _installResults.trySend(installToast(name, key.version, outcome))

            when (outcome) {
                is InstallOutcome.Success -> {
                    _state.value = _state.value.copy(installing = null)
                    DownloadRegistry.clear(key)
                    // The artifact is 38-85 MB and has done its job. Only on SUCCESS:
                    // a failed install keeps its file so a retry does not have to
                    // download the whole build again.
                    withContext(Dispatchers.IO) { file.delete() }
                    refresh()
                }
                is InstallOutcome.Failure -> _state.value = _state.value.copy(
                    installing = null,
                    error = outcome.message,
                    // Surfaces the "Uninstall and retry" action rather than leaving
                    // the user to work out what a signature clash means.
                    conflictWith = if (outcome.reason == InstallFailure.SIGNATURE_CONFLICT) {
                        key.packageName
                    } else null,
                )
            }
        }
    }

    fun uninstall(context: android.content.Context, packageName: String) {
        container.installedApps.uninstall(context, packageName)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null, conflictWith = null, needsInstallPermission = false)
    }

    fun openInstallPermissionSettings(context: android.content.Context) {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
