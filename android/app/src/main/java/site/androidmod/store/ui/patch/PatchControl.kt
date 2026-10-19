package site.androidmod.store.ui.patch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.androidmod.store.patch.PatchRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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
        is PatchStage.SavingOriginal -> "Saving the original…"
        PatchStage.Patching -> "Patching…"
        PatchStage.Signing -> "Signing…"
    }
    is PatchState.AwaitingConfirmation -> "Waiting for your answer…"
    is PatchState.Failed -> state.message
    PatchState.Installing -> "Installing…"
    PatchState.Installed -> "Installed ✓"
    is PatchState.Ready, null -> if (ready) "Ready ✓" else "Download WhatsApp"
}

/** The progress bar's fraction while downloading or saving the original with a known size; null (indeterminate) otherwise. */
fun patchProgress(state: PatchState?): Float? {
    val (done, total) = when (val stage = (state as? PatchState.Running)?.stage) {
        is PatchStage.Downloading -> stage.bytesRead to stage.total
        is PatchStage.SavingOriginal -> stage.bytesDone to stage.total
        else -> return null
    }
    return if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * Whether "Try again" can change anything: not on a phone that is too old. Needs-subscription
 * offers it beside See plans, because a failed state lasts for the process and a user who
 * has just subscribed would otherwise be stuck until restart.
 */
fun patchFailureOffersRetry(reason: PatchFailure): Boolean = reason != PatchFailure.ANDROID_TOO_OLD

private fun percent(read: Long, total: Long): Int = (read * 100 / total).toInt().coerceIn(0, 100)

/**
 * Builds [packageName] on the phone: a button, then the job's progress
 * and its errors. Shared by the move guide and the Library. [onStart] begins (and retries)
 * the job. The features-off question is not asked here but by [PatchQuestionHost].
 */
@Composable
fun PatchControl(
    packageName: String,
    state: PatchState?,
    ready: Boolean,
    onStart: () -> Unit,
    onSubscribe: () -> Unit,
) {
    val text = patchControlText(state, ready)
    when (state) {
        is PatchState.Running -> Progress(text, patchProgress(state))
        is PatchState.AwaitingConfirmation -> {
            // The question itself is asked app-wide (see PatchQuestionHost), never here.
            Progress(text, null)
        }
        is PatchState.Failed -> {
            ErrorBanner(text)
            if (state.reason == PatchFailure.NEEDS_SUBSCRIPTION) {
                MainButton("See plans", onClick = onSubscribe)
            }
            if (patchFailureOffersRetry(state.reason)) {
                MainButton("Try again", onClick = onStart)
            }
        }
        PatchState.Installing -> Progress(text, null)
        PatchState.Installed -> StatusLine(text, PapSuccess)
        is PatchState.Ready, null -> if (ready) StatusLine(text, PapSuccess) else MainButton(text, onClick = onStart)
    }
}

/**
 * The package whose job waits for the features-off answer, with the feature titles; null
 * if none does. With several waiting, the first by package name, so the dialog is stable.
 */
fun pendingQuestion(states: Map<String, PatchState>): Pair<String, List<String>>? =
    states.entries
        .filter { it.value is PatchState.AwaitingConfirmation }
        .minByOrNull { it.key }
        ?.let { it.key to (it.value as PatchState.AwaitingConfirmation).featureTitles }

/**
 * Asks the features-off question for whichever job waits, over whatever screen is on top.
 * Lives once in the signed-in host so the question can be answered from anywhere.
 */
@Composable
fun PatchQuestionHost() {
    val states by PatchRegistry.states.collectAsStateWithLifecycle()
    val (pkg, titles) = pendingQuestion(states) ?: return
    FeaturesOffDialog(titles) { proceed -> PatchRegistry.answer(pkg, proceed) }
}

/** Some visible features of this version are off; patching goes on only if the user says so. */
@Composable
fun FeaturesOffDialog(featureTitles: List<String>, onAnswer: (proceed: Boolean) -> Unit) {
    AlertDialog(
        // Back counts as Cancel: the job must not wait on a dialog nobody can see. A stray
        // tap outside it does not: it would cancel a build the user may want.
        onDismissRequest = { onAnswer(false) },
        properties = DialogProperties(dismissOnClickOutside = false),
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
