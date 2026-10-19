package site.androidmod.store.ui.generic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.androidmod.store.generic.GenericRecord
import site.androidmod.store.generic.InstalledPackage
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.LoadingState
import site.androidmod.store.ui.move.MainButton

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
 * ([lookup], PackageManager); nothing here reaches the network. [confirm] is [confirmPatch];
 * [onStarted] opens the run screen.
 */
@Composable
fun ConfirmScreen(
    packageName: String,
    lookup: (String) -> InstalledPackage?,
    confirm: suspend (InstalledPackage) -> Boolean,
    onStarted: (String) -> Unit,
) {
    val looked by produceState<Lookup>(Lookup.Loading, packageName) {
        value = Lookup.Found(withContext(Dispatchers.IO) { lookup(packageName) })
    }
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
            "Your phone removes its ads. Nothing is uploaded.",
            "${app.label} is removed first, after you confirm. Its data goes with it: sign-ins, downloads, game progress.",
            "Google Play can't update it after this. To update, put the original back, update it in Play, and patch it again.",
            "If the patched copy doesn't work, you can put the original back from the Library.",
        ).forEach { point ->
            Row(modifier = Modifier.padding(top = 10.dp)) {
                Text("•", modifier = Modifier.width(16.dp), style = MaterialTheme.typography.bodyLarge)
                Text(point, style = MaterialTheme.typography.bodyLarge)
            }
        }
        ErrorBanner(error, modifier = Modifier.padding(top = 8.dp))
        MainButton("Patch ${app.label}", enabled = !working) {
            // A second tap before the button redraws disabled.
            if (working) return@MainButton
            working = true
            error = null
            scope.launch {
                val message = try {
                    confirmMessage(app, confirm)
                } finally {
                    working = false
                }
                if (message == null) onStarted(app.packageName) else error = message
            }
        }
    }
}

private sealed interface Lookup {
    data object Loading : Lookup

    /** [app] is null when it isn't installed. */
    data class Found(val app: InstalledPackage?) : Lookup
}
