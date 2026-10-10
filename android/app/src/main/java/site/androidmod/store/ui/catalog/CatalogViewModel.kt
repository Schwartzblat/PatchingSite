package site.androidmod.store.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import site.androidmod.store.StoreApp
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.AuthRepository
import site.androidmod.store.data.BuildVersion
import site.androidmod.store.data.CatalogApp
import site.androidmod.store.data.CatalogRepository
import site.androidmod.store.data.SubscriptionRepository
import site.androidmod.store.data.isAuth
import site.androidmod.store.install.InstalledApp
import site.androidmod.store.install.InstalledApps

data class CatalogState(
    val apps: List<CatalogApp> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /**
     * Null until the answer is known, so the paywall banner never flashes at a
     * subscriber while their status is still loading.
     */
    val subscribed: Boolean? = null,
)

data class VersionsState(
    val versions: List<BuildVersion> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** What is on the device for this package right now; null when it is not installed. */
    val installed: InstalledApp? = null,
)

class CatalogViewModel(
    private val catalog: CatalogRepository = StoreApp.instance.container.catalog,
    private val billing: SubscriptionRepository = StoreApp.instance.container.billing,
    private val auth: AuthRepository = StoreApp.instance.container.auth,
    private val installedApps: InstalledApps = StoreApp.instance.container.installedApps,
) : ViewModel() {

    private val _apps = MutableStateFlow(CatalogState())
    val apps: StateFlow<CatalogState> = _apps.asStateFlow()

    private val _versions = MutableStateFlow(VersionsState())
    val versions: StateFlow<VersionsState> = _versions.asStateFlow()

    /** The in-flight version load, so a superseded one can be cancelled. */
    private var versionsJob: Job? = null

    init {
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            _apps.value = _apps.value.copy(loading = true, error = null)
            _apps.value = try {
                _apps.value.copy(apps = catalog.apps(), loading = false, error = null)
            } catch (error: ApiException) {
                if (error.isAuth) auth.expireSession()
                _apps.value.copy(loading = false, error = error.message)
            }
        }
    }

    /**
     * Drives the subscription banner. Downloads are gated on an active subscription,
     * so a user with none needs to be told here rather than discovering it as a 402
     * three screens later.
     *
     * A failure other than 401 is deliberately silent: the catalogue is still worth
     * showing, and an unknown status simply hides the banner.
     *
     * Driven from the screen rather than from `init` because this view model is
     * Activity-scoped: a user who subscribes and comes back would otherwise keep
     * being told to subscribe until they restart the app.
     */
    fun loadSubscription() {
        viewModelScope.launch {
            try {
                val subscription = billing.status()
                _apps.value = _apps.value.copy(subscribed = subscription?.isActive == true)
            } catch (error: ApiException) {
                if (error.isAuth) auth.expireSession()
            }
        }
    }

    fun loadVersions(packageName: String) {
        // Cancel any load still in flight. Opening app A, going back, then opening
        // app B before A's response lands would otherwise overwrite B's list with
        // A's versions, while the header - resolved separately through appFor() -
        // still reads B. The screen's LaunchedEffect does not cancel this for us:
        // leaving the detail screen does not end a coroutine in viewModelScope.
        versionsJob?.cancel()
        versionsJob = viewModelScope.launch {
            _versions.value = VersionsState(loading = true, installed = installedApps.find(packageName))
            // copy(), not a fresh VersionsState: refreshInstalled() can land while the
            // build list is still loading, and must not be overwritten by it. The
            // fetch is its own statement because copy()'s receiver is evaluated
            // before its arguments - inlined, the state read would predate the
            // network call and resurrect exactly the stale value this avoids.
            _versions.value = try {
                val versions = catalog.versions(packageName)
                _versions.value.copy(versions = versions, loading = false)
            } catch (error: ApiException) {
                if (error.isAuth) auth.expireSession()
                _versions.value.copy(loading = false, error = error.message)
            }
        }
    }

    /**
     * Re-reads the installed version without refetching the build list - a local
     * PackageManager lookup, so it is cheap enough to run on every resume.
     *
     * The screen calls it on resume because anything can change what is installed
     * while this app is in the background (an uninstall from the launcher, a Play
     * update). It also calls it when an install finishes, because a session install
     * reports its result up to ~20s after the system dialog has closed and this
     * screen has already resumed.
     */
    fun refreshInstalled(packageName: String) {
        _versions.value = _versions.value.copy(installed = installedApps.find(packageName))
    }

    fun appFor(packageName: String): CatalogApp? =
        _apps.value.apps.firstOrNull { it.packageName == packageName }
}
