package site.androidmod.store.ui.generic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.androidmod.patch.Capability
import site.androidmod.store.generic.GenericRecord
import site.androidmod.store.generic.InstalledPackage
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.LoadingState
import site.androidmod.store.ui.move.MainButton

/**
 * The confirm screen's two switches: remove ads on, bypass purchases off, and no state kept
 * beyond this visit. Both off leaves nothing to patch, so [canPatch] is false and the Patch
 * button waits. Pure, so the screen is a thin renderer over it.
 */
data class PatchChoice(val ads: Boolean = true, val iap: Boolean = false) {

    /** The mix to patch with, in the engine's enum order. */
    fun capabilities(): Set<Capability> = when {
        ads && iap -> setOf(Capability.ADS, Capability.IAP)
        iap -> setOf(Capability.IAP)
        ads -> setOf(Capability.ADS)
        else -> emptySet()
    }

    /** The Patch button runs only while a capability is chosen. */
    val canPatch: Boolean get() = ads || iap

    /**
     * The first consequence bullet for [label]: what this mix does. The rest of the screen's
     * points are the same whatever is chosen.
     */
    fun firstBullet(label: String): String = when {
        ads && iap -> "Your phone removes its ads and makes its purchases succeed without paying. Nothing is uploaded."
        iap -> "$label's purchases will succeed without paying. Nothing is uploaded."
        ads -> "Your phone removes its ads. Nothing is uploaded."
        else -> "Nothing will be patched."
    }
}

/**
 * Starts patching [app] once the customer confirms: [begin] records it (it blocks, so it runs on
 * [io]), then [start] starts its job. True when the job started; false when Android refused the
 * start, which leaves the record for the run screen's Continue. Throws [begin]'s
 * IllegalStateException, whose message is for the customer, when the app can't be patched again
 * now; nothing is started then.
 */
suspend fun confirmPatch(
    app: InstalledPackage,
    begin: (InstalledPackage) -> GenericRecord,
    io: CoroutineDispatcher = Dispatchers.IO,
    start: (pkg: String, label: String) -> Boolean,
): Boolean {
    val record = withContext(io) { begin(app) }
    return start(record.packageName, record.label)
}

/** When the job couldn't be started, or recording the app failed. */
internal const val PATCHING_NOT_STARTED = "Patching couldn't start. Try again."

/**
 * Runs [confirm] for [app]: null when the run screen should open (the record is there, the job
 * started or not); otherwise what to show. A refusal (begin's IllegalStateException)
 * shows its own words; anything else, such as a record that couldn't be written, says to try again.
 * Cancellation still propagates.
 */
suspend fun confirmMessage(app: InstalledPackage, confirm: suspend (InstalledPackage) -> Boolean): String? = try {
    confirm(app)
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (refused: IllegalStateException) {
    refused.message ?: PATCHING_NOT_STARTED
} catch (error: Exception) {
    PATCHING_NOT_STARTED
}

/**
 * "Patch <App>?", with what happens to it, before anything does. Reads the app from the phone
 * ([lookup], PackageManager); nothing here reaches the network. [confirm] is [confirmPatch], given
 * the chosen mix; [onStarted] opens the run screen. The switches start as [PatchChoice]'s defaults
 * and keep nothing between visits.
 */
@Composable
fun ConfirmScreen(
    packageName: String,
    lookup: (String) -> InstalledPackage?,
    confirm: suspend (InstalledPackage, Set<Capability>) -> Boolean,
    onStarted: (String) -> Unit,
) {
    val looked by produceState<Lookup>(Lookup.Loading, packageName) {
        value = Lookup.Found(withContext(Dispatchers.IO) { lookup(packageName) })
    }
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A fresh choice every time the screen opens: no saved state to restore.
    var choice by remember { mutableStateOf(PatchChoice()) }

    val found = looked as? Lookup.Found
    if (found == null) {
        LoadingState()
        return
    }
    val app = found.app
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (app == null) {
            ErrorBanner("This app isn't installed anymore.")
            return@Column
        }
        Row(modifier = Modifier.padding(top = 16.dp)) { InstalledAppIcon(app.packageName, app.label, size = 52.dp) }
        Text("Patch ${app.label}?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        listOf(
            choice.firstBullet(app.label),
            "${app.label} is removed first, after you confirm. Its data goes with it: sign-ins, downloads, game progress.",
            "Google Play can't update it after this. To update, put the original back, update it in Play, and patch it again.",
            "If the patched copy doesn't work, you can put the original back from the Library.",
        ).forEach { point ->
            Row(modifier = Modifier.padding(top = 10.dp)) {
                Text("•", modifier = Modifier.width(16.dp), style = MaterialTheme.typography.bodyLarge)
                Text(point, style = MaterialTheme.typography.bodyLarge)
            }
        }
        PatchSwitch("Remove ads", choice.ads, onCheckedChange = { choice = choice.copy(ads = it) })
        PatchSwitch("Bypass in-app purchases", choice.iap, onCheckedChange = { choice = choice.copy(iap = it) })
        ErrorBanner(error, modifier = Modifier.padding(top = 8.dp))
        MainButton("Patch ${app.label}", enabled = !working && choice.canPatch) {
            // A second tap before the button redraws disabled.
            if (working || !choice.canPatch) return@MainButton
            val capabilities = choice.capabilities()
            working = true
            error = null
            scope.launch {
                val message = try {
                    confirmMessage(app) { confirm(it, capabilities) }
                } finally {
                    working = false
                }
                if (message == null) onStarted(app.packageName) else error = message
            }
        }
    }
}

/** One of the confirm screen's two choices, with its label. */
@Composable
private fun PatchSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

private sealed interface Lookup {
    data object Loading : Lookup

    /** [app] is null when it isn't installed. */
    data class Found(val app: InstalledPackage?) : Lookup
}
