package site.androidmod.store.ui.generic

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.androidmod.store.R
import site.androidmod.store.generic.GenericPhase
import site.androidmod.store.patch.PatchFailure
import site.androidmod.store.patch.PatchNotifications
import site.androidmod.store.patch.PatchStage
import site.androidmod.store.patch.PatchState
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.LoadingState
import site.androidmod.store.ui.library.GENERIC_UNCHECKED
import site.androidmod.store.ui.library.InstallPermissionBanner
import site.androidmod.store.ui.move.MainButton
import site.androidmod.store.ui.move.Progress
import site.androidmod.store.ui.move.WarningBox
import site.androidmod.store.ui.patch.patchProgress
import site.androidmod.store.ui.theme.PapAccentSoft
import site.androidmod.store.ui.theme.PapDanger
import site.androidmod.store.ui.theme.PapSuccess

/**
 * An app's generic swap, step by step: the job's progress, then Android's dialogs, each started by
 * the customer's tap. Which step it is comes from the phone, re-read on every resume, so leaving for
 * the uninstaller or the installer and coming back lands on the right step.
 */
@Composable
fun RunScreen(
    viewModel: RunViewModel,
    onSubscribe: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onLibrary: () -> Unit,
    onClose: () -> Unit,
    onReport: (packageName: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // In this task and for a result, so the screen knows when the dialog has answered; no NEW_TASK.
    val uninstaller = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onUninstallReturned()
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is RunEvent.LaunchUninstall -> try {
                    uninstaller.launch(
                        Intent(Intent.ACTION_DELETE, "package:${event.packageName}".toUri())
                            .putExtra(Intent.EXTRA_RETURN_RESULT, true),
                    )
                } catch (error: ActivityNotFoundException) {
                    viewModel.onUninstallReturned()
                }
                RunEvent.Closed -> onClose()
                is RunEvent.OpenReport -> onReport(event.packageName)
            }
        }
    }

    if (state.loading) {
        LoadingState()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            InstalledAppIcon(viewModel.packageName, state.label)
            Text(state.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
        }
        val phase = state.phase
        val intent = state.intent
        when {
            state.done -> Done(
                viewModel.packageName,
                state.label,
                onLibrary,
                // After putting the original back: the report the Works-with list most needs.
                onReport = viewModel::onReport.takeIf { state.restoredRecord != null },
            )
            state.unchecked -> {
                Text(GENERIC_UNCHECKED, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
                SecondaryButton("Back to the Library", onClick = onLibrary)
            }
            phase is GenericPhase.Attention -> {
                WarningBox(phase.message)
                ErrorBanner(state.message)
                SecondaryButton("Stop", enabled = !state.busy, onClick = viewModel::onStop)
                SecondaryButton("Back to the Library", onClick = onLibrary)
            }
            phase == null || intent == null -> {
                Text(
                    "Nothing is under way for ${state.label}.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                SecondaryButton("Back to the Library", onClick = onLibrary)
            }
            else -> {
                ErrorBanner(state.message)
                if (state.needsInstallPermission) InstallPermissionBanner(onOpenSettings = onOpenInstallSettings)
                runSteps(state.label, intent, phase, state.job, state.originalSaved).forEachIndexed { index, step ->
                    StepCard(index + 1, step) { StepActions(state, phase, viewModel, onSubscribe) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The buttons of the step under way. Stop and Put the original back wait while Android's uninstall dialog is up. */
@Composable
private fun StepActions(state: RunState, phase: GenericPhase, viewModel: RunViewModel, onSubscribe: () -> Unit) {
    val idle = !state.busy && !state.awaitingUninstall
    val installing = if (state.busy) "Installing…" else null
    when (phase) {
        GenericPhase.SaveAndBuild -> when (val job = state.job) {
            is PatchState.Running, is PatchState.AwaitingConfirmation -> {
                val stage = (job as? PatchState.Running)?.stage ?: PatchStage.Patching
                Progress(PatchNotifications.progressText(state.label, stage), patchProgress(job))
            }
            is PatchState.Failed -> {
                ErrorBanner(job.message)
                if (job.reason == PatchFailure.NEEDS_SUBSCRIPTION) MainButton("See plans", onClick = onSubscribe)
                if (jobOffersRetry(job)) MainButton("Try again", enabled = idle, onClick = viewModel::onRetry)
                SecondaryButton("Stop", enabled = idle, onClick = viewModel::onStop)
            }
            // Not running: the store was closed meanwhile, or Android didn't let the job start.
            else -> {
                MainButton("Continue", enabled = idle, onClick = viewModel::onRetry)
                SecondaryButton("Stop", enabled = idle, onClick = viewModel::onStop)
            }
        }
        is GenericPhase.RemoveOriginal, is GenericPhase.RemovePatched -> {
            if (state.stillInstalled && !state.awaitingUninstall) {
                Text(
                    "${state.label} is still installed. Remove it to continue, or stop.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val remove = if (phase is GenericPhase.RemoveOriginal) "Remove ${state.label}" else "Remove the patched copy"
            MainButton(remove, enabled = idle, onClick = viewModel::onRemove)
            SecondaryButton("Stop", enabled = idle, onClick = viewModel::onStop)
        }
        GenericPhase.InstallPatched -> {
            MainButton(installing ?: "Install the patched copy", enabled = idle, onClick = viewModel::onInstallPatched)
            // Always valid here: the original is saved, and nothing is installed.
            SecondaryButton("Put the original back", enabled = idle, onClick = viewModel::onPutBack)
        }
        GenericPhase.InstallUpdate -> {
            MainButton(installing ?: "Install the update", enabled = idle, onClick = viewModel::onInstallPatched)
            SecondaryButton("Stop", enabled = idle, onClick = viewModel::onStop)
        }
        GenericPhase.InstallOriginal ->
            MainButton(installing ?: "Put the original back", enabled = idle, onClick = viewModel::onInstallOriginal)
        else -> Unit
    }
}

@Composable
private fun Done(packageName: String, label: String, onLibrary: () -> Unit, onReport: (() -> Unit)?) {
    val context = LocalContext.current
    val launch = remember(packageName) { context.packageManager.getLaunchIntentForPackage(packageName) }
    Text("Done", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
    if (launch != null) {
        MainButton("Open $label") {
            try {
                context.startActivity(launch)
            } catch (error: ActivityNotFoundException) {
                // Removed again meanwhile: nothing to open.
            }
        }
    }
    if (onReport != null) SecondaryButton("Report how it works", onClick = onReport)
    SecondaryButton("Back to the Library", onClick = onLibrary)
}

/** One step: its number, a check when done, a mark when it failed, and its buttons while it is under way. */
@Composable
private fun StepCard(number: Int, step: RunStep, body: @Composable () -> Unit) {
    val current = step.status == StepStatus.ACTIVE || step.status == StepStatus.FAILED
    val content = if (step.status == StepStatus.TODO) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (current) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = if (current) 16.dp else 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = CircleShape,
                        color = when (step.status) {
                            StepStatus.DONE -> PapSuccess.copy(alpha = 0.18f)
                            StepStatus.FAILED -> PapDanger.copy(alpha = 0.18f)
                            StepStatus.ACTIVE -> PapAccentSoft
                            StepStatus.TODO -> Color.Transparent
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {}
                    when (step.status) {
                        StepStatus.DONE -> Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = "Done",
                            tint = PapSuccess,
                            modifier = Modifier.size(16.dp),
                        )
                        StepStatus.FAILED -> Icon(
                            painterResource(R.drawable.ic_alert),
                            contentDescription = "Failed",
                            tint = PapDanger,
                            modifier = Modifier.size(16.dp),
                        )
                        else -> Text(
                            number.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (current) MaterialTheme.colorScheme.primary else content,
                        )
                    }
                }
                Text(
                    step.title,
                    style = if (current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    color = content,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (current) {
                Column(modifier = Modifier.padding(top = 12.dp)) { body() }
            }
        }
    }
}

/** A step's second button, outlined in the accent colour as the move guide's are, and able to wait. */
@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) { Text(text) }
}
