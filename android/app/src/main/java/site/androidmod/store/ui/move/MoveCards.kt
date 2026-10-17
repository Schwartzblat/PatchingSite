package site.androidmod.store.ui.move

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.patcher.migrate.Migration
import site.androidmod.store.StoreApp
import site.androidmod.store.move.MoveDirection
import site.androidmod.store.move.MoveGuide
import site.androidmod.store.move.MovePhase
import site.androidmod.store.move.WHATSAPP_PACKAGE
import site.androidmod.store.ui.emailSupport
import site.androidmod.store.ui.openLink
import site.androidmod.store.ui.settings.supportEmailBody

/**
 * The WhatsApp page's entry to the guide. It shows only when it matters: the
 * official WhatsApp is installed, or a move is half done. Our own WhatsApp, or
 * none, shows nothing.
 */
@Composable
fun WhatsAppMoveCard(guide: MoveGuide, onOpen: () -> Unit) {
    when (val phase = guide.phase) {
        MovePhase.NothingToDo -> Unit
        is MovePhase.NotPossibleYet -> MoveSurface(
            title = "Keep your chats",
            text = "Not possible yet. Your WhatsApp (${phase.installed}) is newer than ours " +
                "(${phase.newest}), and WhatsApp can't move chats to an older version. We'll " +
                "publish a newer one soon. Check back in a few days.",
        )
        MovePhase.Attention -> MoveSurface(
            title = "Your chat move needs attention",
            text = "We paused to keep your chats safe.",
            action = "See what to do",
            onAction = onOpen,
        )
        MovePhase.RemovedBeforeSaved -> MoveSurface(
            title = "Your backup was deleted",
            text = "WhatsApp was removed before we could save your backup.",
            action = "See what to do",
            onAction = onOpen,
        )
        is MovePhase.AtStep -> when {
            guide.inProgress -> MoveSurface(
                title = "Finish moving your chats",
                text = "You're on step ${guide.direction.numberOf(phase.step)} of ${guide.direction.steps.size}: " +
                    "${phase.step.title(guide.direction, guide.fromPreviousBuild)}.",
                action = "Continue",
                onAction = onOpen,
            )
            guide.direction == MoveDirection.TO_OURS && guide.fromPreviousBuild -> MoveSurface(
                title = "Move to the new update system",
                text = "Your patched WhatsApp is an older build. Move it once and updates will install with " +
                    "one tap after that. Your chats come along.",
                action = "Start",
                onAction = onOpen,
            )
            guide.direction == MoveDirection.TO_OURS -> MoveSurface(
                title = "Keep your chats",
                text = "You have the regular WhatsApp. Switch to ours without losing your chats, " +
                    "photos and videos. It takes about 10 minutes.",
                action = "Start",
                onAction = onOpen,
            )
            // Ours installed and nothing moved. Going back is offered by GoBackLink,
            // quietly: a card here would read as a nudge to leave.
            else -> Unit
        }
    }
}

/**
 * The WhatsApp page's way back to the official WhatsApp, while ours - or a previous
 * build, whose guide otherwise moves into ours (MoveViewModel.chooseGoingBack) - is
 * installed and nothing has moved. A text button rather than a card: every subscriber with
 * our WhatsApp sees this page, and a card would read as a nudge to leave. A return
 * already under way (a saved copy only, before the park) shows the "Finish moving
 * your chats" card instead, so the link would offer to start what has already started.
 */
@Composable
fun GoBackLink(guide: MoveGuide, onOpen: () -> Unit) {
    if (!guide.oursAtRisk || guide.inProgress) return
    TextButton(onClick = onOpen, modifier = Modifier.padding(top = 4.dp)) {
        Text("Going back to the regular WhatsApp?")
    }
}

/**
 * The Apps tab's reminder while a move is half done. By then WhatsApp may already
 * be removed, and the Apps tab is where the store opens, so this is how the user
 * finds the way back without remembering where the guide lives.
 */
@Composable
fun MoveBanner(guide: MoveGuide, onOpen: () -> Unit) {
    if (!guide.inProgress && guide.phase != MovePhase.Attention) return
    MoveSurface(title = "Finish moving your WhatsApp chats", action = "Continue", onAction = onOpen)
}

@Composable
private fun MoveSurface(title: String, text: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (text != null) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (action != null) {
                Button(onClick = onAction, modifier = Modifier.padding(start = 12.dp)) { Text(action) }
            }
        }
    }
}

/**
 * Asked before anything in the store removes a WhatsApp with chats on it outside
 * the guide, official or ours. Uninstalling either deletes the backup on the
 * phone, so this is the last point at which the chats can still be saved.
 */
@Composable
fun KeepChatsDialog(
    title: String,
    text: String,
    removeLabel: String,
    onKeep: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onKeep) { Text("Keep my chats") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = onRemove) { Text(removeLabel) }
            }
        },
    )
}

/** "Installing removes it" - for an Install that would need the official WhatsApp gone. */
@Composable
fun KeepChatsBeforeInstallDialog(onKeep: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) =
    KeepChatsDialog(
        title = "Keep your chats?",
        text = "Installing our WhatsApp removes the one you have now, and every chat in it. Use the " +
            "guide to bring your chats along.",
        removeLabel = "Remove without keeping",
        onKeep = onKeep,
        onRemove = onRemove,
        onDismiss = onDismiss,
    )

/** "Removing deletes the chats" - for a plain Uninstall of either WhatsApp, worded for the way the guide would go. */
@Composable
fun KeepChatsBeforeRemoveDialog(
    direction: MoveDirection,
    onKeep: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) = KeepChatsDialog(
    title = "Remove WhatsApp?",
    text = "Removing WhatsApp deletes the chats on this phone. " + if (direction == MoveDirection.TO_OFFICIAL) {
        "If you're going back to the regular WhatsApp, use the guide to keep them."
    } else {
        "If you're switching to ours, use the guide to keep them."
    },
    removeLabel = "Remove anyway",
    onKeep = onKeep,
    onRemove = onRemove,
    onDismiss = onDismiss,
)

/**
 * `**bold**` spans, so instructions can name the exact label a user has to find on
 * screen without each line being assembled by hand.
 */
internal fun rich(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { index, part ->
        if (index % 2 == 1) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) }
        } else {
            append(part)
        }
    }
}

/** The system page with this app's All files access switch. */
internal fun openAllFilesSettings(context: Context) {
    try {
        context.startActivity(Migration.allFilesAccessIntent(context))
    } catch (error: ActivityNotFoundException) {
        // Some vendors drop the per-app page; the list of apps is the next best thing.
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

internal fun openWhatsApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE)
    if (launch == null) {
        Toast.makeText(context, "WhatsApp isn't installed.", Toast.LENGTH_LONG).show()
    } else {
        context.startActivity(launch)
    }
}

/** The official WhatsApp's Play Store page: going back to it, or undoing an uninstall that kept its data. */
internal fun openPlayStore(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$WHATSAPP_PACKAGE".toUri()))
    } catch (error: ActivityNotFoundException) {
        openLink(context, "https://play.google.com/store/apps/details?id=$WHATSAPP_PACKAGE")
    }
}

internal fun contactAboutMove(context: Context, summary: String) {
    val deviceContext = StoreApp.instance.container.deviceContext()
    emailSupport(
        context,
        supportEmailBody(deviceContext, "I need help moving my WhatsApp chats.\n\n$summary"),
        subject = "Help moving my WhatsApp chats",
    )
}
