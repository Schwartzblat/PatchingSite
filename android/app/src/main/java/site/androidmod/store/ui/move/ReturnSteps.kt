package site.androidmod.store.ui.move

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.androidmod.store.data.BuildVersion
import site.androidmod.store.download.DownloadState
import site.androidmod.store.move.GuideStep
import site.androidmod.store.move.MoveGuide
import site.androidmod.store.move.StepVariant
import site.androidmod.store.move.WHATSAPP_PACKAGE
import site.androidmod.store.move.backupTime
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.library.InstallPermissionBanner
import site.androidmod.store.ui.library.LibraryState
import site.androidmod.store.ui.library.LibraryViewModel
import site.androidmod.store.ui.theme.PapSuccess

/** The steps going back words its own way. Allow access, Back up and Finish up are shared. */
internal val ReturnOwnSteps = setOf(GuideStep.REMOVE_OLD, GuideStep.INSTALL_NEW, GuideStep.RESTORE)

/**
 * The step bodies only the way back to the official WhatsApp has. The shared ones
 * stay in MoveChatsScreen, so the two directions cannot drift apart where they are
 * the same. Split out because MoveChatsScreen.kt was already over 700 lines.
 */
@Composable
internal fun ReturnStepBody(
    step: GuideStep,
    variant: StepVariant,
    guide: MoveGuide,
    viewModel: MoveViewModel,
    libraryViewModel: LibraryViewModel,
    libraryState: LibraryState,
    newest: BuildVersion?,
    download: DownloadState?,
    versionsError: String?,
    onDownload: () -> Unit,
    onSubscribe: () -> Unit,
    onContact: () -> Unit,
) {
    when (step) {
        GuideStep.REMOVE_OLD -> RemoveOurs(
            variant, guide, viewModel, libraryViewModel, libraryState, newest, download, versionsError, onDownload,
            onSubscribe,
        )
        GuideStep.INSTALL_NEW -> InstallFromPlayStore(variant, guide, viewModel, onContact)
        GuideStep.RESTORE -> RestoreInOfficial(guide, viewModel, onContact)
        else -> Unit
    }
}

/**
 * Step 3. The same order as the move in - park, and only then the uninstall
 * dialog - through the same removeOldWhatsApp. The "kept its data" fix differs:
 * what has to come back is ours, so it is downloaded and installed from here. The
 * install gate lets that through: the engine is at UNINSTALL_WHATSAPP, so there is
 * nothing to put back yet.
 */
@Composable
private fun RemoveOurs(
    variant: StepVariant,
    guide: MoveGuide,
    viewModel: MoveViewModel,
    libraryViewModel: LibraryViewModel,
    libraryState: LibraryState,
    newest: BuildVersion?,
    download: DownloadState?,
    versionsError: String?,
    onDownload: () -> Unit,
    onSubscribe: () -> Unit,
) {
    val context = LocalContext.current
    if (variant == StepVariant.DATA_KEPT) {
        WarningBox(
            title = "WhatsApp kept its data",
            text = "That stops the regular WhatsApp from installing. Your backup is safe. To fix it:",
        )
        Numbered(
            "Download our WhatsApp and tap **Install WhatsApp**. Don't open it.",
            "Tap **Remove our WhatsApp**. This time leave **Keep app data** unticked.",
        )
        val key = viewModel.downloadedKey()
        if (key == null) {
            DownloadControl(newest, download, versionsError, viewModel::retryVersions, onDownload, onSubscribe)
        } else {
            if (libraryState.needsInstallPermission) {
                InstallPermissionBanner(onOpenSettings = { libraryViewModel.openInstallPermissionSettings(context) })
            }
            ErrorBanner(libraryState.error)
            MainButton(
                text = if (libraryState.installing?.packageName == WHATSAPP_PACKAGE) "Installing…" else "Install WhatsApp",
                enabled = libraryState.installing == null,
            ) { libraryViewModel.install(key) }
        }
        return
    }
    if (variant == StepVariant.BACKUP_SAVED) {
        StatusLine("Your backup is saved in a safe place ✓", PapSuccess)
        Lead("Now remove our WhatsApp. Android will ask you to confirm.")
    } else {
        Lead(
            "We'll move your backup to a safe place, then Android will ask you to remove our WhatsApp. " +
                "Your backup stays safe."
        )
    }
    WarningBox(
        text = "If you see **Keep app data**, leave it unticked. Ticking it stops the regular WhatsApp " +
            "from installing.",
    )
    Text(rich("Then tap **Uninstall**."), style = MaterialTheme.typography.bodyMedium)
    if (variant != StepVariant.BACKUP_SAVED) {
        Hint("Do this right after your backup. Messages that arrive in between won't come along.")
    }
    MainButton("Remove our WhatsApp", onClick = viewModel::removeOldWhatsApp)
    // Before the park only: once parked, BACKUP_SAVED already says it is safe.
    if (variant == StepVariant.NORMAL) SavedCopyNotes(guide, viewModel)
}

/**
 * Step 4. The backup stays parked while the Play Store installs, and
 * WhatsAppInstallWatcher puts it back once WhatsApp is installed, before it can
 * register. The watcher is best effort (a dead or cached store hears the install
 * late, if at all), so the words ask the user to come back before opening
 * WhatsApp rather than promise it happens by itself. The two variants are what that
 * leaves: the watcher has not run yet, and the Play Store's copy is older than the
 * backup, which it would refuse.
 */
@Composable
private fun InstallFromPlayStore(variant: StepVariant, guide: MoveGuide, viewModel: MoveViewModel, onContact: () -> Unit) {
    val context = LocalContext.current
    MediaLostWarning(guide)
    when (variant) {
        StepVariant.PUT_BACK_NOW -> {
            Lead("WhatsApp is installed. Put your backup back before you open it.")
            MainButton("Put my backup back", onClick = viewModel::putBackNow)
        }
        StepVariant.PLAY_TOO_OLD -> {
            WarningBox(
                title = "Don't open WhatsApp yet",
                text = "The WhatsApp from the Play Store (${guide.installedVersion}) is older than ours " +
                    "(${guide.savedVersion}), so it can't read your backup. Your backup is safe. When the Play " +
                    "Store has updated WhatsApp, come back here before you open it, and we'll put your backup back.",
            )
            MainButton("Open Play Store") { openPlayStore(context) }
            TextButton(onClick = onContact) { Text("Contact us") }
        }
        else -> {
            StatusLine("Your backup is safe ✓", PapSuccess)
            Lead("Now install the regular WhatsApp, then come back here. We'll put your backup back before you open it.")
            Numbered(
                "Tap **Open Play Store**.",
                "Tap **Install**.",
                "When it finishes, come back here. Don't tap **Open** in the Play Store yet.",
            )
            MainButton("Open Play Store") { openPlayStore(context) }
        }
    }
}

/**
 * Step 5. The move in's restore, plus a date check: the official WhatsApp may find
 * an older Google Drive backup from before the user switched to ours, and restoring
 * that would quietly drop every message since. Not yet seen on a device; until it
 * is, the check stops the user and sends them to us.
 */
@Composable
private fun RestoreInOfficial(guide: MoveGuide, viewModel: MoveViewModel, onContact: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var wrongBackup by remember { mutableStateOf(false) }
    val restoreLine = if (guide.savedBackupMs > 0) {
        "When you see **Backup found**, check its date. It should say " +
            "${backupTime(guide.savedBackupMs, state.readAt)}. Then tap **Restore**. Never tap **Skip**."
    } else {
        "When you see **Backup found**, tap **Restore**. Never tap **Skip**."
    }
    Lead("Open WhatsApp and set it up. It will find your backup.")
    Numbered(
        *listOfNotNull(
            "Tap **Open WhatsApp**.",
            "Tap **Agree and continue**.",
            "Enter the same phone number as before, then the 6-digit code from the SMS.",
            "If WhatsApp asks for your two-step PIN, enter it.",
            "If WhatsApp asks to access your contacts or files, tap **Allow**.",
            restoreLine,
            "Enter your backup password or 64-digit key.".takeIf { guide.encrypted },
            "Wait until the restore finishes, tap **Next**, and enter your name.",
        ).toTypedArray()
    )
    MainButton("Open WhatsApp") { openWhatsApp(context) }
    OutlinedMainButton("My chats are back", onClick = viewModel::confirmChatsBack)
    TextButton(onClick = { wrongBackup = true }, modifier = Modifier.padding(top = 4.dp)) {
        Text("WhatsApp found an older backup, or none")
    }
    if (wrongBackup) {
        AlertDialog(
            onDismissRequest = { wrongBackup = false },
            title = { Text("Not the right backup?") },
            text = {
                Text(
                    "Don't tap Restore or Skip. It may be an old Google Drive backup from before you switched " +
                        "to ours. Your chats are still on this phone. Contact us and we'll help you finish."
                )
            },
            confirmButton = {
                Button(onClick = { wrongBackup = false; onContact() }) { Text("Contact us") }
            },
            dismissButton = { TextButton(onClick = { wrongBackup = false }) { Text("Close") } },
        )
    }
}
