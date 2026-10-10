package site.androidmod.store.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.formatBytes
import site.androidmod.store.ui.library.InstallPermissionBanner
import site.androidmod.store.update.UpdateCheck
import site.androidmod.store.update.UpdatePhase
import site.androidmod.store.update.UpdateState
import site.androidmod.store.update.versionLabel

/**
 * The Apps-tab offer. Renders nothing unless a newer build is published, so an
 * up-to-date store - the usual case - shows no trace of the updater.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onUpdate: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    val available = state.available ?: return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Store update available",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        describe(available),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.phase == UpdatePhase.Idle) {
                    Button(onClick = onUpdate, modifier = Modifier.padding(start = 12.dp)) { Text("Update") }
                }
            }
            UpdateProgress(state.phase)
        }
    }
    UpdateProblems(state, onOpenInstallSettings)
}

/** The body of Settings' "App version" card. */
@Composable
fun AppVersionContent(
    state: UpdateState,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    Text(
        "Installed: ${versionLabel(state.installed.versionName, state.installed.versionCode)}",
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        updateStatus(state),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
    UpdateProgress(state.phase)

    // Both hidden while anything runs: the view model would refuse the tap anyway,
    // and a live button next to a progress bar invites a second one.
    if (state.phase == UpdatePhase.Idle) {
        Row(modifier = Modifier.padding(top = 12.dp)) {
            if (state.available != null) {
                Button(onClick = onUpdate, modifier = Modifier.padding(end = 8.dp)) { Text("Update") }
            }
            OutlinedButton(onClick = onCheck) { Text("Check for updates") }
        }
    }
    UpdateProblems(state, onOpenInstallSettings)
}

@Composable
private fun UpdateProgress(phase: UpdatePhase) {
    when (phase) {
        is UpdatePhase.Downloading -> {
            val modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            if (phase.total > 0) {
                LinearProgressIndicator(
                    progress = { (phase.bytesRead.toFloat() / phase.total).coerceIn(0f, 1f) },
                    modifier = modifier,
                )
            } else {
                LinearProgressIndicator(modifier = modifier)
            }
            ProgressCaption(
                if (phase.total > 0) {
                    "Downloading ${formatBytes(phase.bytesRead)} of ${formatBytes(phase.total)}"
                } else "Downloading ${formatBytes(phase.bytesRead)}"
            )
        }
        UpdatePhase.Installing -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            // Said up front because Android kills this app to replace it and does
            // not reopen it. Without the warning, a successful update looks exactly
            // like a crash.
            ProgressCaption("Installing. Patched Store will close to finish the update.")
        }
        UpdatePhase.Idle, UpdatePhase.Checking -> Unit
    }
}

@Composable
private fun ProgressCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun UpdateProblems(state: UpdateState, onOpenInstallSettings: () -> Unit) {
    ErrorBanner(state.error)
    if (state.needsInstallPermission) InstallPermissionBanner(onOpenSettings = onOpenInstallSettings)
}

private fun describe(available: UpdateCheck.Available): String =
    "${versionLabel(available.versionName, available.versionCode)} · ${formatBytes(available.size)}"

/** The one-line answer under "Installed: ..." in Settings. */
internal fun updateStatus(state: UpdateState): String = when {
    state.phase == UpdatePhase.Checking -> "Checking for updates..."
    // Before `check`: a failed re-check keeps the earlier answer, which would
    // otherwise hide that this one failed.
    state.checkError != null -> "Could not check for updates: ${state.checkError}"
    else -> when (val check = state.check) {
        is UpdateCheck.Available -> "Update available: ${describe(check)}"
        UpdateCheck.UpToDate -> "Up to date"
        UpdateCheck.Unknown -> "The server has no build to compare against."
        null -> ""
    }
}
