package site.androidmod.store.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.androidmod.store.R
import site.androidmod.store.download.DownloadKey
import site.androidmod.store.download.DownloadRegistry
import site.androidmod.store.generic.GenericEntry
import site.androidmod.store.generic.GenericPhase
import site.androidmod.store.ui.AppIcon
import site.androidmod.store.ui.EmptyState
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.LoadingState
import site.androidmod.store.ui.formatBytes
import site.androidmod.store.ui.generic.InstalledAppIcon
import site.androidmod.store.move.WHATSAPP_PACKAGE
import site.androidmod.store.patch.PatchRegistry
import site.androidmod.store.patch.PatchState
import site.androidmod.store.ui.patch.PatchControl
import androidx.compose.runtime.LaunchedEffect
import site.androidmod.store.ui.move.KeepChatsBeforeInstallDialog
import site.androidmod.store.ui.move.KeepChatsBeforeRemoveDialog
import site.androidmod.store.ui.move.MoveViewModel
import site.androidmod.store.ui.theme.PapSuccess
import site.androidmod.store.ui.theme.PapWarning

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    moveViewModel: MoveViewModel,
    onOpenMoveGuide: () -> Unit,
    onSubscribe: () -> Unit = {},
    /** A generic patch's run screen. */
    onOpenGenericRun: (String) -> Unit = {},
    /** A generic patch's report screen. */
    onReport: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val moveGuide by moveViewModel.guide.collectAsStateWithLifecycle()
    // Which warning is up: a WhatsApp with chats on it - official, or ours - is about
    // to be removed outside the guide, which deletes the chats on the phone. Null when
    // none is.
    var removeWarning by remember { mutableStateOf<RemoveWarning?>(null) }
    // The finished patch whose saved original the customer asked to delete; null when none is.
    var confirmDelete by remember { mutableStateOf<GenericEntry?>(null) }
    val downloads by DownloadRegistry.downloads.collectAsStateWithLifecycle()
    val patchStates by PatchRegistry.states.collectAsStateWithLifecycle()
    // Hoisted: every system-intent call from this screen needs the Activity
    // context, not the application one.
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The system uninstaller is another activity and reports nothing back, so
    // returning to this screen is the only signal that anything changed. Without
    // this the one screen whose job is showing installed state keeps claiming an
    // app is installed immediately after its own Uninstall button removed it.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
                moveViewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // An on-device update installs from the service, which reports only through the
    // registry: when one lands, what is installed has changed.
    val installedCount = patchStates.values.count { it == PatchState.Installed }
    LaunchedEffect(installedCount) { if (installedCount > 0) viewModel.refresh() }

    // A Refresh or Put back started from a generic entry continues on its run screen.
    val openRun by rememberUpdatedState(onOpenGenericRun)
    LaunchedEffect(viewModel) { viewModel.genericRuns.collect { openRun(it) } }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Two surfaces because they have two owners and two lifetimes: a failed
        // catalogue read is cleared by the next refresh, a failed install is not.
        ErrorBanner(state.loadError)
        ErrorBanner(state.error)
        ErrorBanner(state.genericError)

        if (state.needsInstallPermission) {
            InstallPermissionBanner(onOpenSettings = { viewModel.openInstallPermissionSettings(context) })
        }

        state.conflictWith?.let { packageName ->
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    when {
                        packageName == WHATSAPP_PACKAGE && moveGuide.officialAtRisk ->
                            removeWarning = RemoveWarning.BEFORE_INSTALL
                        // Mid-move, WhatsApp's folder may hold the backup itself: only the
                        // guide knows whether removing this WhatsApp is safe right now.
                        packageName == WHATSAPP_PACKAGE && moveGuide.inProgress -> onOpenMoveGuide()
                        else -> viewModel.uninstall(context, packageName)
                    }
                }) { Text("Uninstall and retry") }
                OutlinedButton(
                    onClick = { viewModel.clearError() },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Dismiss") }
            }
        }

        val nothingListed = state.entries.isEmpty() && state.generic.isEmpty()
        when {
            state.loading && nothingListed -> LoadingState()
            nothingListed && state.loadError == null -> EmptyState(
                icon = R.drawable.ic_library,
                title = "Nothing here yet",
                message = "Apps you download and install will show up here.",
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(state.entries, key = { it.packageName }) { entry ->
                    // A build already on disk for this package is installable now.
                    val ready = downloads.keys.firstOrNull {
                        it.packageName == entry.packageName && DownloadRegistry.fileFor(it) != null
                    }
                    LibraryCard(
                        entry = entry,
                        ready = ready,
                        installing = state.installing,
                        patchState = patchStates[entry.packageName],
                        moveInProgress = moveGuide.inProgress,
                        onUpdate = { viewModel.startUpdate(entry.packageName, entry.name) },
                        onMove = onOpenMoveGuide,
                        onSubscribe = onSubscribe,
                        onInstall = { ready?.let(viewModel::install) },
                        onUninstall = {
                            val whatsApp = entry.packageName == WHATSAPP_PACKAGE
                            when {
                                whatsApp && (moveGuide.officialAtRisk || moveGuide.oursAtRisk) ->
                                    removeWarning = RemoveWarning.PLAIN
                                // Mid-move the guide owns removing WhatsApp; see "Uninstall and retry".
                                whatsApp && moveGuide.inProgress -> onOpenMoveGuide()
                                else -> viewModel.uninstall(context, entry.packageName)
                            }
                        },
                    )
                }
                // Below the catalogue's; keyed apart from them.
                items(state.generic, key = { "generic:${it.record.packageName}" }) { entry ->
                    val pkg = entry.record.packageName
                    GenericLibraryCard(entry = entry, busy = state.genericBusy) { action ->
                        when (action) {
                            GenericAction.CONTINUE -> onOpenGenericRun(pkg)
                            GenericAction.REFRESH -> viewModel.refreshGeneric(entry.record)
                            GenericAction.PUT_BACK -> viewModel.putBackGeneric(entry.record)
                            GenericAction.REPORT -> onReport(pkg)
                            GenericAction.DELETE_ORIGINAL -> confirmDelete = entry
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { entry ->
        DeleteOriginalDialog(
            entry = entry,
            onDelete = {
                confirmDelete = null
                viewModel.deleteGenericOriginal(entry.record)
            },
            onDismiss = { confirmDelete = null },
        )
    }

    val removeWhatsApp = {
        removeWarning = null
        viewModel.uninstall(context, WHATSAPP_PACKAGE)
    }
    val keepChats = {
        removeWarning = null
        onOpenMoveGuide()
    }
    when (removeWarning) {
        RemoveWarning.BEFORE_INSTALL -> KeepChatsBeforeInstallDialog(
            onKeep = keepChats, onRemove = removeWhatsApp, onDismiss = { removeWarning = null },
        )
        RemoveWarning.PLAIN -> KeepChatsBeforeRemoveDialog(
            direction = moveGuide.direction,
            onKeep = keepChats, onRemove = removeWhatsApp, onDismiss = { removeWarning = null },
        )
        null -> Unit
    }
}

private fun patchBusy(state: PatchState?) = state is PatchState.Running ||
    state is PatchState.AwaitingConfirmation || state == PatchState.Installing

/**
 * Whether the card shows the patch job's progress instead of the Update button: while it
 * runs, and for a failure or "Installed" only while the update is still on offer (the
 * registry keeps both for the process, so a later offer gets its button back).
 */
internal fun showsPatchControl(state: PatchState?, action: LibraryAction): Boolean = when (state) {
    is PatchState.Running, is PatchState.AwaitingConfirmation, PatchState.Installing -> true
    is PatchState.Failed, PatchState.Installed -> action == LibraryAction.UPDATE
    is PatchState.Ready, null -> false
}

/** Which of the two "your chats will go" warnings to show; see KeepChatsDialog. */
private enum class RemoveWarning { BEFORE_INSTALL, PLAIN }

@Composable
private fun LibraryCard(
    entry: LibraryEntry,
    ready: DownloadKey?,
    installing: DownloadKey?,
    patchState: PatchState?,
    moveInProgress: Boolean,
    onUpdate: () -> Unit,
    onMove: () -> Unit,
    onSubscribe: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val action = libraryAction(entry)
    val showPatchControl = showsPatchControl(patchState, action)
    // Colour carries the same three-way distinction the text does, so
    // an available update is spottable without reading every row.
    val (status, tint) = when {
        entry.installed == null ->
            "Not installed" to MaterialTheme.colorScheme.onSurfaceVariant
        entry.updateAvailable ->
            "Installed ${entry.installed.versionName} · ${entry.latest} available" to PapWarning
        action == LibraryAction.MOVE ->
            "Installed ${entry.installed.versionName} · one-time move needed" to PapWarning
        else ->
            "Installed ${entry.installed.versionName} · up to date" to PapSuccess
    }
    LibraryCardFrame(
        icon = { AppIcon(packageName = entry.packageName, name = entry.name, size = 44.dp) },
        name = entry.name,
        status = status,
        statusColor = tint,
    ) {
        if (showPatchControl) {
            Column(modifier = Modifier.padding(top = 14.dp)) {
                PatchControl(
                    packageName = entry.packageName,
                    state = patchState,
                    ready = false,
                    onStart = onUpdate,
                    onSubscribe = onSubscribe,
                )
            }
        }

        if (ready != null || entry.installed != null) {
            Row(modifier = Modifier.padding(top = 14.dp)) {
                if (ready != null) {
                    Button(onClick = onInstall, enabled = installing == null) {
                        Text(if (installing == ready) "Installing…" else "Install ${ready.version}")
                    }
                }
                val label = libraryActionLabel(action)
                if (label != null && !showPatchControl) {
                    Button(
                        onClick = if (action == LibraryAction.MOVE) onMove else onUpdate,
                        // Mid-move the guide owns installing WhatsApp.
                        enabled = libraryActionEnabled(action, moveInProgress),
                    ) { Text(label) }
                }
                if (entry.installed != null) {
                    OutlinedButton(
                        onClick = onUninstall,
                        enabled = !patchBusy(patchState),
                        modifier = Modifier.padding(start = if (ready != null || (label != null && !showPatchControl)) 8.dp else 0.dp),
                    ) { Text("Uninstall") }
                }
            }
        }
    }
}

/** Every Library card: the app's icon, its name and status line, then [content]. */
@Composable
private fun LibraryCardFrame(
    icon: @Composable () -> Unit,
    name: String,
    status: String,
    statusColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            content()
        }
    }
}

/** An app patched on this phone: its step, its hints under a finished patch, and what it offers (see [genericActions]). */
@Composable
private fun GenericLibraryCard(entry: GenericEntry, busy: Boolean, onAction: (GenericAction) -> Unit) {
    val label = entry.record.label
    val phase = entry.phase
    val tint = when {
        showsGenericHints(entry) -> PapSuccess
        phase == GenericPhase.Removed || phase == GenericPhase.Unpatched -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> PapWarning
    }
    LibraryCardFrame(
        icon = { InstalledAppIcon(entry.record.packageName, label) },
        name = label,
        status = genericStatusLine(entry),
        statusColor = tint,
    ) {
        if (showsGenericHints(entry)) {
            entry.hint?.let { hint ->
                Text(
                    refreshHintText(label, hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = PapWarning,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Text(
                genericUpdateHint(label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        val actions = genericActions(entry)
        if (actions.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions.forEachIndexed { index, action ->
                    val text = genericActionLabel(action, entry)
                    // The first is the card's main action, as Update and Install are on the others.
                    if (index == 0) {
                        Button(onClick = { onAction(action) }, enabled = !busy) { Text(text) }
                    } else {
                        OutlinedButton(onClick = { onAction(action) }, enabled = !busy) { Text(text) }
                    }
                }
            }
        }
    }
}

/** Asked before the saved original of a finished patch goes: without it, the Library can't put the original back. */
@Composable
private fun DeleteOriginalDialog(entry: GenericEntry, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val label = entry.record.label
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete the saved original?") },
        text = {
            Text(
                "This frees ${formatBytes(entry.savedOriginalBytes)}. Without it, the original of $label can't be put " +
                    "back from the Library. You can still remove $label and install it again from Google Play.",
            )
        },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Shown when an install was refused because the "Install unknown apps" appop is not
 * granted. Shared with AppDetailScreen: its Install button runs the same
 * LibraryViewModel.install, and without this banner there a tap there looked like
 * it did nothing at all.
 */
@Composable
fun InstallPermissionBanner(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Permission needed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "This app needs permission to install other apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Open settings") }
        }
    }
}
