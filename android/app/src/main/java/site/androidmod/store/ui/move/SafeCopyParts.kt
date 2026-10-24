package site.androidmod.store.ui.move

import androidx.compose.foundation.layout.Column
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
import com.patcher.migrate.SpareCopy
import site.androidmod.store.move.CancelOutcome
import site.androidmod.store.move.GuideStep
import site.androidmod.store.move.MoveDirection
import site.androidmod.store.move.MoveGuide
import site.androidmod.store.move.SaveProblem
import site.androidmod.store.move.StepVariant
import site.androidmod.store.move.backupTime
import site.androidmod.store.move.cancelOutcome
import site.androidmod.store.ui.formatBytes
import site.androidmod.store.ui.theme.PapSuccess

// The guide's parts from the 2026-10-10 spec: what has been saved before step 4,
// what removing WhatsApp by hand cost, and Finish up's last check. Kept out of
// MoveChatsScreen.kt, which was already over 700 lines (ReturnSteps.kt's reason too).

/**
 * Steps 2-4, before the park: whether the backup is saved yet, what removing
 * WhatsApp any other way would cost, and the way out. On 2026-10-10 WhatsApp was
 * removed from Settings at this point with nothing saved, and the chats went with
 * it; this is why every step before the park now says what has been kept.
 */
@Composable
internal fun SavedCopyNotes(guide: MoveGuide, viewModel: MoveViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    // "step 4" going in; going back, the removal is step 3.
    val removeStep = guide.direction.numberOf(GuideStep.REMOVE_OLD)
    val saved = guide.saved
    if (saved != null) {
        StatusLine("Your backup is saved in a safe place ✓", PapSuccess)
        Hint("The backup from ${backupTime(saved.atMs, state.readAt)}, ${formatBytes(saved.bytes)}.")
        Hint(
            "If you remove WhatsApp yourself, your chats are still safe, but photos and videos aren't. " +
                "Use step $removeStep to keep everything."
        )
        if (guide.reinstalled) {
            WarningBox(
                text = "WhatsApp was installed again during the move. Check that all your chats are there " +
                    "before you back up. If they aren't, contact us: your earlier backup (from " +
                    "${backupTime(saved.atMs, state.readAt)}, ${formatBytes(saved.bytes)}) is saved on this phone.",
            )
        }
    } else {
        when (val problem = state.saveProblem) {
            is SaveProblem.NoRoom -> WarningBox(
                text = "There isn't room to save a copy (needs ${formatBytes(problem.neededBytes)}). Step " +
                    "$removeStep still keeps your backup safe, but don't remove WhatsApp any other way.",
            )
            is SaveProblem.Failed -> {
                WarningBox(
                    text = "We couldn't save a copy of your backup. Step $removeStep still keeps your backup " +
                        "safe, but don't remove WhatsApp any other way.",
                )
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Details") }
                if (open) Details(problem.details)
            }
            null -> Hint(
                "Come back here after you back up, and don't remove WhatsApp any other way than step $removeStep."
            )
        }
    }
}

/**
 * "Cancel the move", under the current step wherever the guide offers it
 * (MoveGuide.canCancel: every step but Finish up, since 2026-10-24). The confirmation
 * says what happens to the backup from where the move stands.
 */
@Composable
internal fun CancelMove(guide: MoveGuide, viewModel: MoveViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }, modifier = Modifier.padding(top = 4.dp)) { Text("Cancel the move") }
    if (confirm) {
        val text = when (state.status?.let(::cancelOutcome) ?: CancelOutcome.CHATS_STAY) {
            CancelOutcome.CHATS_STAY -> if (guide.parkedAtMs > 0) {
                "We'll put your backup back in your WhatsApp. Your chats stay in it, and you can start again " +
                    "whenever you like."
            } else {
                "Your chats stay in your WhatsApp, and you can start again whenever you like."
            }
            CancelOutcome.BACKUP_BACK ->
                "Your old WhatsApp is already removed, so it can't come back. We'll put your backup back where " +
                    "WhatsApp looks for it and stop the guide. Then install WhatsApp, and it will find your " +
                    "backup when you set it up."
            CancelOutcome.BACKUP_IN_NEW ->
                "We'll stop the guide. Your backup stays in WhatsApp's folder, and we keep a spare copy on " +
                    "this phone."
        }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Cancel the move?") },
            text = { Text(text) },
            confirmButton = {
                Button(onClick = { confirm = false; viewModel.cancelMove() }) { Text("Cancel the move") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep going") } },
        )
    }
}

/**
 * Step 4 once WhatsApp ran on after the park (StepVariant.USED_SINCE_PARK), in either
 * direction. The parked backup is missing everything since, and removing WhatsApp
 * would delete the newer folder, so the only way offered is back: the folder goes back
 * beside WhatsApp's newer files (Parking.unpark), and the user starts again.
 */
@Composable
internal fun UsedSinceParkBody(guide: MoveGuide, viewModel: MoveViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val parked = if (guide.parkedAtMs > 0) " (${backupTime(guide.parkedAtMs, state.readAt)})" else ""
    WarningBox(
        title = "Don't remove WhatsApp",
        text = "WhatsApp has kept running since we saved your backup$parked, so that backup may not have " +
            "your newest messages. Removing WhatsApp now would lose them.",
    )
    Lead("Put your backup back, then start again with a fresh backup. Nothing is deleted.")
    MainButton("Put my backup back and start again", onClick = viewModel::cancelMove)
}

/**
 * The install step after WhatsApp was removed by hand: the chats come back from
 * the saved copy, but the media were in the folder the removal deleted.
 */
@Composable
internal fun MediaLostWarning(guide: MoveGuide) {
    if (!guide.mediaLost) return
    WarningBox(
        text = "WhatsApp was removed before step ${guide.direction.numberOf(GuideStep.REMOVE_OLD)}. Your " +
            "chats are saved, but the photos, videos, voice notes and documents that were on this phone are gone.",
    )
}

/**
 * Finish up. Nothing can be removed until the move is proven: the user's "My chats
 * are back" and the new WhatsApp's own backup (the engine's DONE). Until then this
 * step asks for that backup. When it came out much smaller than the one put back,
 * it sends the user to us with everything kept.
 */
@Composable
internal fun FinishBody(variant: StepVariant, guide: MoveGuide, viewModel: MoveViewModel, onContact: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val app = if (guide.direction == MoveDirection.TO_OFFICIAL) "the regular WhatsApp" else "the new WhatsApp"
    when (variant) {
        StepVariant.LAST_CHECK -> {
            Lead(
                "One last check: in $app, tap **Settings** > **Chats** > **Chat backup** > **Back up**, then " +
                    "come back here."
            )
            MainButton("Open WhatsApp") { openWhatsApp(context) }
        }
        StepVariant.BACKUP_TOO_SMALL -> {
            WarningBox(
                text = "Your new backup is much smaller than the saved one (${formatBytes(guide.newBackupSize)} " +
                    "vs ${formatBytes(guide.putBackSize)}). Some chats may be missing. Don't remove anything. " +
                    "Contact us.",
            )
            MainButton("Contact us", onClick = onContact)
        }
        else -> {
            var confirm by remember { mutableStateOf(false) }
            if (guide.putBackSize > 0) {
                StatusLine(
                    "New backup ${formatBytes(guide.newBackupSize)}, saved one ${formatBytes(guide.putBackSize)} ✓",
                    PapSuccess,
                )
            }
            Lead(
                "We kept a spare copy of your chats until you're sure everything is back. Check your chats " +
                    "in WhatsApp, then remove the spare copy to free up space."
            )
            SpareCopyList(state.spareCopies, state.readAt)
            MainButton("Remove the spare copy") { confirm = true }
            if (confirm) {
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text("Remove the spare copy?") },
                    text = {
                        Text("Only do this once you've checked that your chats are in $app. This can't be undone.")
                    },
                    confirmButton = {
                        Button(onClick = { confirm = false; viewModel.removeSpareCopy() }) { Text("Remove") }
                    },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                )
            }
        }
    }
}

/** What "Remove the spare copy" deletes, before it does: this move's copies, then each earlier one. */
@Composable
private fun SpareCopyList(copies: List<SpareCopy>, now: Long) {
    if (copies.isEmpty()) return
    val thisMove = copies.filterNot { it.isEarlier }
    val earlier = copies.filter { it.isEarlier }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        if (thisMove.isNotEmpty()) {
            Bullet(
                "This move's copy, from ${backupTime(thisMove.maxOf { it.modifiedMs }, now)}: " +
                    formatBytes(thisMove.sumOf { it.bytes })
            )
        }
        earlier.forEach { Bullet("An earlier copy, from ${backupTime(it.modifiedMs, now)}: ${formatBytes(it.bytes)}") }
        Bullet("Total: ${formatBytes(copies.sumOf { it.bytes })}")
    }
}

@Composable
private fun Bullet(text: String) {
    Text("• $text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
}
