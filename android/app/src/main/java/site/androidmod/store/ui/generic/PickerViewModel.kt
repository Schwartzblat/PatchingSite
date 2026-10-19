package site.androidmod.store.ui.generic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import site.androidmod.patch.ApkFacts
import site.androidmod.patch.GenericChecks
import site.androidmod.store.generic.GenericRepository
import site.androidmod.store.generic.InstalledPackage
import site.androidmod.store.generic.Inspection
import site.androidmod.store.generic.PickerBadge
import site.androidmod.store.generic.PickerRow
import site.androidmod.store.generic.filterRows
import site.androidmod.store.generic.inspectSafely
import site.androidmod.store.generic.pickerBadge
import site.androidmod.store.patch.SigningKeyStore

/** [rows] are the listed apps that match [query]; [loading] lasts until the list is read. */
data class PickerState(
    val rows: List<PickerRow> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * The phone's installed apps, each badged for generic patching. Rows start at Checking and are
 * inspected in the background, [INSPECT_AT_ONCE] at a time, top of the list first. Nothing here
 * reaches the network: [storePackages] (our store's own apps) and [tested] (the Works-with packages,
 * null while the generic status hasn't loaded) are what earlier screens already loaded, and
 * without both nothing is listed. Every source runs on [io].
 */
class PickerViewModel(
    private val listApps: () -> List<InstalledPackage>,
    private val inspect: (List<File>) -> ApkFacts,
    private val obb: (String) -> Boolean?,
    private val storePackages: () -> Set<String>,
    private val tested: () -> Set<String>?,
    private val sharedCerts: () -> Set<String>,
    private val userCert: () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(PickerState())
    val state: StateFlow<PickerState> = _state.asStateFlow()

    /** Every listed row, unfiltered. */
    private val all = MutableStateFlow<List<PickerRow>>(emptyList())

    init {
        viewModelScope.launch { load() }
    }

    fun onQuery(query: String) {
        _state.update { it.copy(query = query, rows = filterRows(all.value, query)) }
    }

    private suspend fun load() {
        val sources = try {
            withContext(io) {
                val store = storePackages()
                val tested = tested()
                if (store.isEmpty() || tested == null) null else Sources(store, tested, listApps(), sharedCerts(), userCert())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(loading = false, error = LIST_FAILED) }
            return
        }
        if (sources == null) {
            _state.update { it.copy(loading = false, error = STORE_LIST_MISSING) }
            return
        }

        val badge = { app: InstalledPackage, inspection: Inspection, obb: Boolean? ->
            pickerBadge(app, inspection, obb, sources.store, sources.tested, sources.sharedCerts, sources.userCert)
        }
        all.value = sources.apps.map { PickerRow(it, badge(it, Inspection.Pending, null)) }
        publish(loading = false)

        // In the order shown, so the top of the list resolves first; the semaphore is fair.
        val gate = Semaphore(INSPECT_AT_ONCE)
        filterRows(all.value, "").filter { it.badge == PickerBadge.Checking }.forEach { row ->
            viewModelScope.launch {
                val resolved = gate.withPermit {
                    withContext(io) { badge(row.app, inspectSafely { inspect(row.app.apks) }, obbSafely(row.app.packageName)) }
                }
                all.update { rows -> rows.map { if (it.app.packageName == row.app.packageName) it.copy(badge = resolved) else it } }
                publish()
            }
        }
    }

    /** Whether the app keeps files in Android/obb; null (unknown, which doesn't block) when that can't be read. */
    private fun obbSafely(packageName: String): Boolean? = try {
        obb(packageName)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }

    /** Rows land from several coroutines; each publish reads the latest rows. */
    private fun publish(loading: Boolean? = null) {
        _state.update { it.copy(rows = filterRows(all.value, it.query), loading = loading ?: it.loading) }
    }

    private class Sources(
        val store: Set<String>,
        val tested: Set<String>,
        val apps: List<InstalledPackage>,
        val sharedCerts: Set<String>,
        val userCert: String?,
    )

    companion object {
        const val INSPECT_AT_ONCE = 2
        const val STORE_LIST_MISSING = "Open the Apps tab again to load the store's list, then try again."
        const val LIST_FAILED = "Your apps couldn't be listed. Go back and try again."
    }
}

/**
 * The picker as the store wires it, and as NoNetworkTest builds it: every source is on the phone.
 * [listApps], [obb] and [sharedCerts] read PackageManager and storage; [GenericChecks.inspect]
 * reads each app's own files; [generic]'s status is the last one loaded, never refreshed here; and
 * the user's key is the cached one, never fetched.
 */
fun pickerViewModel(
    listApps: () -> List<InstalledPackage>,
    obb: (String) -> Boolean?,
    sharedCerts: () -> Set<String>,
    generic: GenericRepository,
    storePackages: () -> Set<String>,
    signingKeys: SigningKeyStore,
    deviceSdk: Int,
    deviceAbis: List<String>,
    io: CoroutineDispatcher = Dispatchers.IO,
): PickerViewModel = PickerViewModel(
    listApps = listApps,
    inspect = { apks -> GenericChecks.inspect(apks, deviceSdk, deviceAbis) },
    obb = obb,
    storePackages = storePackages,
    tested = { generic.status.value?.worksWith?.mapTo(HashSet()) { it.packageName } },
    sharedCerts = sharedCerts,
    // Lowercase, as the installed signers are.
    userCert = { signingKeys.cached()?.certSha256?.lowercase() },
    io = io,
)

/** Where tapping a row goes. */
sealed interface PickerTap {
    /** One of our store's own apps: its store page, never the generic path. */
    data class StorePage(val packageName: String) : PickerTap
    data class Confirm(val packageName: String) : PickerTap

    /** Checking, already patched or can't patch: the row is greyed and says why. */
    data object None : PickerTap
}

fun pickerTap(row: PickerRow): PickerTap = when {
    row.badge == PickerBadge.InOurStore -> PickerTap.StorePage(row.app.packageName)
    row.pickable -> PickerTap.Confirm(row.app.packageName)
    else -> PickerTap.None
}

fun pickerBadgeText(badge: PickerBadge): String = when (badge) {
    PickerBadge.Checking -> "Checking…"
    PickerBadge.Tested -> "Tested"
    PickerBadge.NotTested -> "Not tested"
    PickerBadge.InOurStore -> "In our store"
    PickerBadge.AlreadyPatched -> "Already patched"
    is PickerBadge.CantPatch -> "Can't patch"
}
