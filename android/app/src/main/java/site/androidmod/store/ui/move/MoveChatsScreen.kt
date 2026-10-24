package site.androidmod.store.ui.move

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.androidmod.store.R
import site.androidmod.store.data.BuildVersion
import site.androidmod.store.download.DownloadKey
import site.androidmod.store.download.DownloadRegistry
import site.androidmod.store.download.DownloadService
import site.androidmod.store.download.DownloadState
import site.androidmod.store.move.BackupAge
import site.androidmod.store.move.CancelOutcome
import site.androidmod.store.move.GuideStep
import site.androidmod.store.move.MoveDirection
import site.androidmod.store.move.MoveGuide
import site.androidmod.store.move.MovePhase
import site.androidmod.store.move.StepVariant
import site.androidmod.store.move.WHATSAPP_PACKAGE
import site.androidmod.store.move.backupAgo
import site.androidmod.store.move.backupTime
import site.androidmod.store.patch.PatchFailure
import site.androidmod.store.patch.message
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.LoadingState
import site.androidmod.store.ui.formatBytes
import site.androidmod.store.ui.library.InstallPermissionBanner
import site.androidmod.store.ui.library.LibraryState
import site.androidmod.store.ui.library.LibraryViewModel
import site.androidmod.store.ui.theme.PapAccentSoft
import site.androidmod.store.ui.theme.PapSuccess
import site.androidmod.store.ui.theme.PapWarning
import site.androidmod.store.ui.theme.PapWarningBg

/**
 * The step-by-step guide for moving WhatsApp chats into our build, or back to the official WhatsApp.
 *
 * Written for someone who has never opened a file manager: every step says what
 * to tap in words they will see on screen, there is one main button at a time,
 * and nothing technical is shown unless they open "Details" to send it to us.
 * Which step is current comes from the phone itself (MoveViewModel re-reads it on
 * every resume), so leaving half-way - into WhatsApp, Settings, the uninstaller -
 * and coming back always lands on the right step.
 */
@Composable
fun MoveChatsScreen(
    viewModel: MoveViewModel,
    libraryViewModel: LibraryViewModel,
    onSubscribe: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val libraryState by libraryViewModel.state.collectAsStateWithLifecycle()
    val downloads by DownloadRegistry.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Every step but the downloads happens in another app - WhatsApp, Settings,
    // the uninstaller, the installer - and returning here is the only signal.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // A session install reports up to ~20s after its dialog closes, after the resume.
    LaunchedEffect(libraryState.installing) {
        if (libraryState.installing == null) viewModel.refresh()
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                MoveEvent.LaunchUninstall -> libraryViewModel.uninstall(context, WHATSAPP_PACKAGE)
            }
        }
    }

    val busy = state.busy ?: if (libraryState.movingBackup) "Putting your backup back…" else null
    if (busy != null) BusyDialog(busy)

    val newest = state.versions.firstOrNull()
    val newestKey = newest?.let { DownloadKey(WHATSAPP_PACKAGE, it.version) }
    val contact = { contactAboutMove(context, viewModel.supportSummary()) }

    if (state.status == null) {
        LoadingState()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val finished = guide.finished
        val cancelled = guide.cancelled
        if (finished == null && cancelled != null) {
            CancelledPanel(cancelled, onStartAgain = viewModel::markStarted, onContact = contact, onBack = onBack)
        } else if (finished != null) {
            if (finished == MoveDirection.TO_OFFICIAL) {
                Panel(
                    title = "All done",
                    text = "Your chats are in the regular WhatsApp. Updates now come from the Play Store. To come " +
                        "back to ours, open WhatsApp in this app and tap **Start**.",
                    action = "Done",
                    onAction = onBack,
                )
            } else {
                Panel(
                    title = "All done",
                    text = "Your chats are in the new WhatsApp. Updates to it come through this app.",
                    action = "Back to WhatsApp",
                    onAction = onBack,
                )
            }
        } else when (val phase = guide.phase) {
            MovePhase.Attention -> AttentionPanel(
                details = state.status?.let { it.blockers + it.warnings }.orEmpty(),
                onContact = contact,
            )
            is MovePhase.NotPossibleYet -> Panel(
                title = "Not possible yet",
                text = "Your WhatsApp (${phase.installed}) is newer than ours (${phase.newest}), and " +
                    "WhatsApp can't move chats to an older version. We'll publish a newer one soon. " +
                    "Check back in a few days.",
                action = "Back",
                onAction = onBack,
            )
            MovePhase.AndroidTooOld -> Panel(
                title = "Not possible on this phone",
                text = PatchFailure.ANDROID_TOO_OLD.message(),
                action = "Back",
                onAction = onBack,
            )
            MovePhase.NothingToDo -> if (state.keyUnknown) {
                // Hidden on purpose (hideMoveWhileKeyUnknown): saying "nothing to move" would be false.
                Panel(
                    title = "Checking your WhatsApp…",
                    text = "We couldn't check which WhatsApp you have yet. Check your connection and try again.",
                    action = "Try again",
                    onAction = { viewModel.refresh(retryKey = true) },
                )
            } else {
                Panel(
                    title = "Nothing to move",
                    text = "WhatsApp isn't installed, so there are no chats to move.",
                    action = "Back",
                    onAction = onBack,
                )
            }
            MovePhase.RemovedBeforeSaved -> RemovedBeforeSavedPanel(onContact = contact, onBack = onBack)
            is MovePhase.AtStep -> {
                if ((phase.step == GuideStep.ALLOW_ACCESS || phase.step == GuideStep.BACK_UP) && !guide.inProgress) {
                    BeforeYouStart(guide.direction, newest)
                }
                state.failure?.let { failure ->
                    FailurePanel(failure, onContact = contact, onDismiss = viewModel::dismissFailure)
                }
                if (offersUndoGoingBack(state.previous, state.previousGoesBack, guide.inProgress)) {
                    TextButton(onClick = { viewModel.chooseGoingBack(false) }) {
                        Text("Move to the new update system instead")
                    }
                }
                guide.direction.steps.forEach { step ->
                    StepRow(
                        number = guide.direction.numberOf(step),
                        title = step.title(guide.direction, guide.fromPreviousBuild),
                        done = guide.isDone(step),
                        current = step == phase.step,
                    ) {
                        StepBody(
                            step = step,
                            variant = phase.variant,
                            guide = guide,
                            viewModel = viewModel,
                            libraryViewModel = libraryViewModel,
                            libraryState = libraryState,
                            newest = newest,
                            download = newestKey?.let { downloads[it] },
                            versionsError = state.versionsError,
                            passwordKnown = state.acks.password,
                            onSubscribe = onSubscribe,
                            onContact = contact,
                        )
                        // That step's own button is the way back already.
                        if (guide.canCancel && phase.variant != StepVariant.USED_SINCE_PARK) {
                            CancelMove(guide, viewModel)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepBody(
    step: GuideStep,
    variant: StepVariant,
    guide: MoveGuide,
    viewModel: MoveViewModel,
    libraryViewModel: LibraryViewModel,
    libraryState: LibraryState,
    newest: BuildVersion?,
    download: DownloadState?,
    versionsError: String?,
    passwordKnown: Boolean,
    onSubscribe: () -> Unit,
    onContact: () -> Unit,
) {
    val context = LocalContext.current
    val onDevice = viewModel.state.collectAsStateWithLifecycle().value.onDevice
    val startDownload = {
        newest?.let {
            DownloadService.start(
                context, WHATSAPP_PACKAGE, it.version, it.filename, name = "WhatsApp",
            )
        }
        Unit
    }
    // Going back shares Allow access, Back up and Finish up with the move in; the rest are its own.
    if (guide.direction == MoveDirection.TO_OFFICIAL && step in ReturnOwnSteps) {
        ReturnStepBody(
            step = step,
            variant = variant,
            guide = guide,
            viewModel = viewModel,
            libraryViewModel = libraryViewModel,
            libraryState = libraryState,
            newest = newest,
            download = download,
            versionsError = versionsError,
            onDownload = startDownload,
            onSubscribe = onSubscribe,
            onContact = onContact,
        )
        return
    }
    when (step) {
        GuideStep.ALLOW_ACCESS -> {
            Lead(
                if (variant == StepVariant.ACCESS_REVOKED) {
                    "File access was turned off, so we can't see your backup right now. Turn it back on " +
                        "to continue."
                } else {
                    "This app needs permission to reach WhatsApp's backup on your phone. Android calls it " +
                        "All files access."
                }
            )
            Numbered(
                "Tap **Open settings**.",
                "Turn on **Allow access to manage all files**.",
                "Press Back to return here.",
            )
            MainButton("Open settings") { openAllFilesSettings(context) }
        }

        GuideStep.BACK_UP -> {
            Lead(
                "WhatsApp keeps a copy of your chats on your phone. Make a fresh one now so your newest " +
                    "messages come along."
            )
            Numbered(
                "Tap **Open WhatsApp**.",
                "Tap **⋮** at the top right, then **Settings**.",
                "Tap **Chats**, then **Chat backup**.",
                "Tap **Back up** and wait until it finishes.",
                "Come back to this app.",
            )
            BackupStatus(guide, viewModel)
            SavedCopyNotes(guide, viewModel)
            if (guide.encrypted) {
                WarningBox(
                    title = "Your backup is locked with a password",
                    text = "You turned on end-to-end encrypted backup in WhatsApp. To get your chats back " +
                        "you'll need its password, or the 64-digit key. Without it, nobody can open the " +
                        "backup: not us, and not WhatsApp.",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = passwordKnown, onCheckedChange = viewModel::knowPassword)
                    Text("I know my backup password or key", style = MaterialTheme.typography.bodyMedium)
                }
            }
            MainButton("Open WhatsApp") { openWhatsApp(context) }
        }

        GuideStep.DOWNLOAD -> {
            Lead(
                if (onDevice) {
                    ON_DEVICE_DOWNLOAD_LEAD
                } else {
                    "Download it now, while your old WhatsApp still works. Nothing changes on your phone yet."
                }
            )
            GuideDownloadControl(viewModel, newest, download, startDownload, onSubscribe)
            SavedCopyNotes(guide, viewModel)
        }

        GuideStep.REMOVE_OLD -> when (variant) {
            StepVariant.DATA_KEPT -> {
                WarningBox(
                    title = "WhatsApp kept its data",
                    text = "That stops the new WhatsApp from installing. Your backup is safe. To fix it:",
                )
                Numbered(
                    "Tap **Open Play Store** and install WhatsApp again. Don't open it.",
                    "Come back here and tap **Remove the old WhatsApp**. This time leave **Keep app data** " +
                        "unticked.",
                )
                MainButton("Open Play Store") { openPlayStore(context) }
            }
            StepVariant.USED_SINCE_PARK -> UsedSinceParkBody(guide, viewModel)
            else -> {
                if (variant == StepVariant.BACKUP_SAVED) {
                    StatusLine("Your backup is saved in a safe place ✓", PapSuccess)
                    Lead("Now remove the old WhatsApp. Android will ask you to confirm.")
                } else {
                    Lead(
                        "We'll move your backup to a safe place, then Android will ask you to remove the " +
                            "old WhatsApp. Your backup stays safe."
                    )
                }
                WarningBox(
                    text = "If you see **Keep app data**, leave it unticked. Ticking it stops the new " +
                        "WhatsApp from installing.",
                )
                Text(rich("Then tap **Uninstall**."), style = MaterialTheme.typography.bodyMedium)
                if (variant != StepVariant.BACKUP_SAVED) {
                    Hint("Do this right after your backup. Messages that arrive in between won't come along.")
                }
                MainButton("Remove the old WhatsApp", onClick = viewModel::removeOldWhatsApp)
                // Before the park only: once parked, BACKUP_SAVED already says it is safe.
                if (variant == StepVariant.NORMAL) SavedCopyNotes(guide, viewModel)
            }
        }

        GuideStep.INSTALL_NEW -> {
            MediaLostWarning(guide)
            if (variant == StepVariant.NEEDS_DOWNLOAD) {
                Lead("The download was cleared. Download it again. Your backup is safe.")
                GuideDownloadControl(viewModel, newest, download, startDownload, onSubscribe)
            } else {
                Lead("We'll put your backup back where WhatsApp looks for it, then install the new WhatsApp.")
                Numbered(
                    "Tap **Install WhatsApp**.",
                    "If Android asks \"Install this app?\", tap **Install**.",
                    "If Google Play Protect warns you, tap **Install anyway**. It does this for every app " +
                        "from outside the Play Store.",
                )
                if (libraryState.needsInstallPermission) {
                    InstallPermissionBanner(onOpenSettings = { libraryViewModel.openInstallPermissionSettings(context) })
                }
                ErrorBanner(libraryState.error)
                val key = viewModel.downloadedKey()
                MainButton(
                    text = if (libraryState.installing?.packageName == WHATSAPP_PACKAGE) "Installing…" else "Install WhatsApp",
                    enabled = key != null && libraryState.installing == null,
                ) { key?.let(libraryViewModel::install) }
            }
        }

        GuideStep.RESTORE -> {
            var notFound by remember { mutableStateOf(false) }
            Lead("Open the new WhatsApp and set it up. It will find your backup.")
            Numbered(
                *listOfNotNull(
                    "Tap **Open WhatsApp**.",
                    "Tap **Agree and continue**.",
                    "Enter the same phone number as before, then the 6-digit code from the SMS.",
                    "If WhatsApp asks for your two-step PIN, enter it.",
                    "If WhatsApp asks to access your contacts or files, tap **Allow**.",
                    "When you see **Backup found**, tap **Restore**. Never tap **Skip**.",
                    "Enter your backup password or 64-digit key.".takeIf { guide.encrypted },
                    "Wait until the restore finishes, tap **Next**, and enter your name.",
                ).toTypedArray()
            )
            MainButton("Open WhatsApp") { openWhatsApp(context) }
            OutlinedMainButton("My chats are back", onClick = viewModel::confirmChatsBack)
            TextButton(onClick = { notFound = true }, modifier = Modifier.padding(top = 4.dp)) {
                Text("WhatsApp didn't find my backup")
            }
            if (notFound) {
                AlertDialog(
                    onDismissRequest = { notFound = false },
                    title = { Text("WhatsApp didn't find your backup?") },
                    text = {
                        Text(
                            "Don't tap Skip, and don't remove WhatsApp. Your chats are still on this phone. " +
                                "Contact us and we'll help you finish."
                        )
                    },
                    confirmButton = {
                        Button(onClick = { notFound = false; onContact() }) { Text("Contact us") }
                    },
                    dismissButton = { TextButton(onClick = { notFound = false }) { Text("Close") } },
                )
            }
        }

        GuideStep.FINISH -> FinishBody(variant, guide, viewModel, onContact)
    }
}

/** The backup's age in words, and the way out when it is old. */
@Composable
private fun BackupStatus(guide: MoveGuide, viewModel: MoveViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var askOld by remember { mutableStateOf(false) }
    val now = state.readAt
    val time = backupTime(guide.newestBackupMs, now)
    when (guide.backupAge) {
        BackupAge.NONE -> StatusLine("No backup found yet.", MaterialTheme.colorScheme.onSurfaceVariant)
        BackupAge.FRESH -> StatusLine("Last backup: ${backupAgo(guide.newestBackupMs, now)} ✓", PapSuccess)
        BackupAge.STALE -> if (state.acks.oldBackup) {
            StatusLine("Using the backup from $time.", PapWarning)
        } else {
            StatusLine("Last backup: $time. Make a new one now.", PapWarning)
            TextButton(onClick = { askOld = true }) { Text("Use the backup from $time anyway") }
        }
    }
    if (askOld) {
        AlertDialog(
            onDismissRequest = { askOld = false },
            title = { Text("Use an older backup?") },
            text = { Text("Messages from after $time won't be moved to the new WhatsApp.") },
            confirmButton = {
                Button(onClick = { askOld = false; viewModel.useOldBackup() }) { Text("Use it anyway") }
            },
            dismissButton = { TextButton(onClick = { askOld = false }) { Text("Cancel") } },
        )
    }
}

/** Download, its progress, and the two failures that need different fixes. */
@Composable
internal fun DownloadControl(
    newest: BuildVersion?,
    download: DownloadState?,
    versionsError: String?,
    onRetryVersions: () -> Unit,
    onDownload: () -> Unit,
    onSubscribe: () -> Unit,
) {
    if (newest == null) {
        if (versionsError != null) {
            ErrorBanner(versionsError)
            MainButton("Try again", onClick = onRetryVersions)
        } else {
            Hint("Looking for the newest version…")
        }
        return
    }
    when (download) {
        is DownloadState.Queued -> Progress("Queued…", null)
        is DownloadState.Preparing -> Progress(download.message ?: "Preparing…", null)
        is DownloadState.Transferring -> Progress(
            if (download.total > 0) "Downloading… ${download.bytesRead * 100 / download.total}%" else
                "Downloading… ${formatBytes(download.bytesRead)}",
            if (download.total > 0) download.bytesRead.toFloat() / download.total else null,
        )
        is DownloadState.Done -> StatusLine("Downloaded ${newest.version} ✓", PapSuccess)
        is DownloadState.Failed -> if (download.needsSubscription) {
            Lead("You need a subscription to download it.")
            MainButton("See plans", onClick = onSubscribe)
        } else {
            ErrorBanner(download.message)
            MainButton("Try again", onClick = onDownload)
        }
        null -> MainButton("Download (${formatBytes(newest.size)})", onClick = onDownload)
    }
}

@Composable
private fun StepRow(number: Int, title: String, done: Boolean, current: Boolean, body: @Composable () -> Unit) {
    val content = if (current || done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (current) MaterialTheme.colorScheme.surfaceContainerHigh else
                MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = if (current) 16.dp else 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(26.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = when {
                            done -> PapSuccess.copy(alpha = 0.18f)
                            current -> PapAccentSoft
                            else -> Color.Transparent
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {}
                    if (done) {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = "Done",
                            tint = PapSuccess,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Text(
                            number.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (current) MaterialTheme.colorScheme.primary else content,
                        )
                    }
                }
                Text(
                    title,
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

@Composable
private fun BeforeYouStart(direction: MoveDirection, newest: BuildVersion?) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Before you start",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            val size = newest?.let { "a ${formatBytes(it.size)}" } ?: "a large"
            val lines = if (direction == MoveDirection.TO_OFFICIAL) {
                listOf(
                    "About 10 minutes, without interruptions.",
                    "The Play Store, signed in, and Wi-Fi: WhatsApp is a large download.",
                    "This phone's SIM card for your WhatsApp number. WhatsApp sends you a code by SMS.",
                    "Your two-step verification PIN, if you set one in WhatsApp.",
                    "You can come back to ours any time, and your chats come along the same way.",
                )
            } else {
                listOf(
                    "About 10 minutes, without interruptions.",
                    "Wi-Fi: the new WhatsApp is $size download.",
                    "This phone's SIM card for your WhatsApp number. WhatsApp sends you a code by SMS.",
                    "Your two-step verification PIN, if you set one in WhatsApp.",
                )
            }
            lines.forEach { line ->
                Row(modifier = Modifier.padding(top = 6.dp)) {
                    Text("•", modifier = Modifier.width(16.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AttentionPanel(details: List<String>, onContact: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Panel(
        title = "We paused to keep your chats safe",
        text = "Something on your phone isn't the way we expected, so we stopped before anything could go " +
            "wrong. Don't remove or reinstall WhatsApp for now. Contact us and we'll help you finish.",
        action = "Contact us",
        onAction = onContact,
    )
    if (details.isNotEmpty()) {
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Details") }
        if (open) Details(details)
    }
}

/**
 * The 2026-10-10 loss, said plainly: WhatsApp was removed before the store could
 * save its backup, and the backup on the phone went with it. Only a Google Drive
 * backup can bring the chats back now, restored into the Play Store's WhatsApp.
 */
@Composable
private fun RemovedBeforeSavedPanel(onContact: () -> Unit, onBack: () -> Unit) {
    Panel(
        title = "Your backup was deleted",
        text = "WhatsApp was removed before we could save your backup, and removing WhatsApp deletes the " +
            "backup on this phone. If WhatsApp was backing up to Google Drive, install WhatsApp from the Play " +
            "Store and restore from Google Drive. You can check the backup's date at drive.google.com, under " +
            "Backups. Then you can move your chats again.",
        action = "Contact us",
        onAction = onContact,
    )
    TextButton(onClick = onBack) { Text("Back") }
}

/**
 * After "Cancel the move" (MoveAcks.cancelled), until the user starts again: what
 * became of the backup, which the steps could no longer say, since with the move
 * over the phone alone reads as a move not yet begun - or, with no WhatsApp, as
 * nothing to move.
 */
@Composable
private fun CancelledPanel(outcome: CancelOutcome, onStartAgain: () -> Unit, onContact: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    when (outcome) {
        CancelOutcome.CHATS_STAY -> {
            Panel(
                title = "Move cancelled",
                text = "Your chats stay in your WhatsApp. You can start again whenever you like.",
                action = "Start again",
                onAction = onStartAgain,
            )
            TextButton(onClick = onBack) { Text("Done") }
        }
        CancelOutcome.BACKUP_BACK -> {
            Panel(
                title = "Move cancelled",
                text = "Your backup is back where WhatsApp looks for it. Install WhatsApp from the Play Store, " +
                    "and when you set it up, tap **Restore**. If WhatsApp doesn't offer your backup, don't tap " +
                    "**Skip**. Contact us: we keep a spare copy on this phone.",
                action = "Open Play Store",
                onAction = { openPlayStore(context) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onContact) { Text("Contact us") }
                TextButton(onClick = onBack) { Text("Done") }
            }
        }
        CancelOutcome.BACKUP_IN_NEW -> Panel(
            title = "Move cancelled",
            text = "Your backup is in WhatsApp's folder. If your chats aren't back yet, WhatsApp offers the " +
                "backup when you set it up.",
            action = "Done",
            onAction = onBack,
        )
    }
}

@Composable
private fun FailurePanel(failure: MoveFailure, onContact: () -> Unit, onDismiss: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    ErrorBanner(failure.message)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onContact) { Text("Contact us") }
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Details") }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
    if (open) Details(failure.details)
}

@Composable
internal fun Details(lines: List<String>) {
    Text(
        lines.joinToString("\n\n"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Panel(title: String, text: String, action: String, onAction: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(rich(text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        MainButton(action, onClick = onAction)
    }
}

@Composable
internal fun Lead(text: String) {
    Text(rich(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
internal fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
internal fun StatusLine(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
internal fun Numbered(vararg lines: String) {
    Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { index, line ->
            Row {
                Text(
                    "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(22.dp),
                )
                Text(rich(line), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Yellow, for the one thing on a step that goes wrong if it is missed. */
@Composable
internal fun WarningBox(text: String, title: String? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        shape = MaterialTheme.shapes.small,
        color = PapWarningBg,
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Icon(
                painterResource(R.drawable.ic_alert),
                contentDescription = null,
                tint = PapWarning,
                modifier = Modifier.size(18.dp).padding(top = 2.dp),
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = PapWarning)
                }
                Text(
                    rich(text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = if (title != null) 4.dp else 0.dp),
                )
            }
        }
    }
}

@Composable
internal fun Progress(label: String, fraction: Float?) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
internal fun MainButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) { Text(text) }
}

/**
 * A step's second button. Accent colours set by hand: this Material version draws
 * an outlined button's text in a muted tone that read as disabled on the device.
 */
@Composable
internal fun OutlinedMainButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) { Text(text) }
}

/**
 * Not dismissable: these are the moves of the backup itself, a few seconds long,
 * and a second tap or a back press in the middle is exactly what must not happen.
 */
@Composable
private fun BusyDialog(text: String) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(text, modifier = Modifier.padding(start = 16.dp))
            }
        },
    )
}
