package site.androidmod.store.ui.move

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.androidmod.store.data.BuildVersion
import site.androidmod.store.download.DownloadState
import site.androidmod.store.move.WHATSAPP_PACKAGE
import site.androidmod.store.patch.PatchMode
import site.androidmod.store.patch.PatchRegistry
import site.androidmod.store.patch.PatchService
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.patch.PatchControl

/** Android refused to start the build's service (the app was not in the foreground). */
private const val GUIDE_NOT_STARTED = "Open the app and try again."

/** The lead above the download when WhatsApp is built on the phone (the DOWNLOAD step's). */
internal const val ON_DEVICE_DOWNLOAD_LEAD =
    "Download it now, while your old WhatsApp still works. It comes from whatsapp.com and is patched on " +
        "your phone. Nothing changes on your phone yet."

/**
 * The guide's download, wherever it appears (step 3, the install step's "cleared" variant,
 * the return's "kept its data" fix): the server's build off the phone, the patch job on it.
 */
@Composable
internal fun GuideDownloadControl(
    viewModel: MoveViewModel,
    newest: BuildVersion?,
    download: DownloadState?,
    onDownload: () -> Unit,
    onSubscribe: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.onDevice) {
        DownloadControl(newest, download, state.versionsError, viewModel::retryVersions, onDownload, onSubscribe)
        return
    }
    val context = LocalContext.current
    val patches by PatchRegistry.states.collectAsStateWithLifecycle()
    val ready = viewModel.downloadedKey() != null
    if (state.latestNeedsSubscription && !ready) {
        ErrorBanner(state.versionsError)
        MainButton("See plans", onClick = onSubscribe)
        return
    }
    // Why the latest check failed (a store update, the network); the download itself asks again.
    if (!ready) ErrorBanner(state.versionsError)
    PatchControl(
        packageName = WHATSAPP_PACKAGE,
        // An earlier Library update's "Installed" is not this step's download.
        state = guidePatchState(patches[WHATSAPP_PACKAGE]),
        ready = ready,
        onStart = {
            if (!PatchService.start(context, WHATSAPP_PACKAGE, PatchMode.GUIDE, name = "WhatsApp")) {
                Toast.makeText(context, GUIDE_NOT_STARTED, Toast.LENGTH_LONG).show()
            }
        },
        onSubscribe = onSubscribe,
    )
}
