package site.androidmod.store.ui.patch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import site.androidmod.store.patch.PatchFailure
import site.androidmod.store.patch.PatchStage
import site.androidmod.store.patch.PatchState
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.move.MainButton
import site.androidmod.store.ui.move.Progress
import site.androidmod.store.ui.move.StatusLine
import site.androidmod.store.ui.theme.PapSuccess

/**
 * What the control says for [state]. [ready] is whether the build is already on disk:
 * a job still running outranks it, a failure is shown too, and a Ready state whose file
 * is gone is not ready. Pure, so the words can be tested.
 */
fun patchControlText(state: PatchState?, ready: Boolean): String = when (state) {
    is PatchState.Running -> when (val stage = state.stage) {
        PatchStage.CheckingVersion -> "Checking version…"
        is PatchStage.Downloading ->
            if (stage.total > 0) "Downloading WhatsApp ${percent(stage.bytesRead, stage.total)}%" else "Downloading WhatsApp…"
        PatchStage.Patching -> "Patching…"
        PatchStage.Signing -> "Signing…"
    }
    is PatchState.AwaitingConfirmation -> "Waiting for your answer…"
    is PatchState.Failed -> state.message
    PatchState.Installing -> "Installing…"
    PatchState.Installed -> "Installed ✓"
    is PatchState.Ready, null -> if (ready) "Ready ✓" else "Download WhatsApp"
}

/** The progress bar's fraction while downloading with a known size; null (indeterminate) otherwise. */
fun patchProgress(state: PatchState?): Float? {
    val stage = (state as? PatchState.Running)?.stage as? PatchStage.Downloading ?: return null
    return if (stage.total > 0) (stage.bytesRead.toFloat() / stage.total).coerceIn(0f, 1f) else null
}

private fun percent(read: Long, total: Long): Int = (read * 100 / total).toInt().coerceIn(0, 100)

/**
 * Builds [packageName] on the phone: a button, then the job's progress, the
 * features-off question, and its errors. Shared by the move guide and the Library.
 * [onStart] begins (and retries) the job; [onAnswer] is the user's go-ahead.
 */
@Composable
fun PatchControl(
    packageName: String,
    state: PatchState?,
    ready: Boolean,
    onStart: () -> Unit,
    onSubscribe: () -> Unit,
    onAnswer: (proceed: Boolean) -> Unit = { site.androidmod.store.patch.PatchRegistry.answer(packageName, it) },
) {
    val text = patchControlText(state, ready)
    when (state) {
        is PatchState.Running -> Progress(text, patchProgress(state))
        is PatchState.AwaitingConfirmation -> {
            Progress(text, null)
            FeaturesOffDialog(state.featureTitles, onAnswer)
        }
        is PatchState.Failed -> {
            ErrorBanner(text)
            if (state.reason == PatchFailure.NEEDS_SUBSCRIPTION) {
                MainButton("See plans", onClick = onSubscribe)
            } else {
                MainButton("Try again", onClick = onStart)
            }
        }
        PatchState.Installing -> Progress(text, null)
        PatchState.Installed -> StatusLine(text, PapSuccess)
        is PatchState.Ready, null -> if (ready) StatusLine(text, PapSuccess) else MainButton(text, onClick = onStart)
    }
}

/** Some visible features of this version are off; patching goes on only if the user says so. */
@Composable
fun FeaturesOffDialog(featureTitles: List<String>, onAnswer: (proceed: Boolean) -> Unit) {
    AlertDialog(
        // Back counts as Cancel: the job must not wait on a dialog nobody can see.
        onDismissRequest = { onAnswer(false) },
        title = { Text("Some features are off in this version") },
        text = {
            Column {
                featureTitles.forEach { title -> Text("• $title", modifier = Modifier.padding(bottom = 4.dp)) }
                Text("They'll come back in a later update. Patch anyway?", modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { Button(onClick = { onAnswer(true) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Cancel") } },
    )
}
