package site.androidmod.store.ui.library

import site.androidmod.store.generic.GenericEntry
import site.androidmod.store.generic.GenericPhase
import site.androidmod.store.generic.RefreshHint
import site.androidmod.store.ui.formatBytes

/** What a generic entry's card offers. */
enum class GenericAction { CONTINUE, REFRESH, PUT_BACK, REPORT, DELETE_ORIGINAL, FORGET }

/**
 * A flow under way only continues (on the run screen). A finished patch is refreshed, put back,
 * reported on, and its saved original deleted when there is one. Every other finished entry has a
 * way out: an app removed by hand has its saved original put back, or is forgotten when nothing is
 * saved; an app whose original is installed again is forgotten (GenericFlow.forget). Attention
 * offers nothing: putting back while another copy is installed would stop at "changed outside the
 * store", and its message says to uninstall first, which makes it Removed.
 */
internal fun genericActions(entry: GenericEntry): List<GenericAction> {
    val saved = entry.record.original.isNotEmpty()
    return when {
        entry.record.intent != null -> listOf(GenericAction.CONTINUE)
        entry.phase == GenericPhase.Patched -> listOfNotNull(
            GenericAction.REFRESH,
            GenericAction.PUT_BACK,
            GenericAction.REPORT,
            GenericAction.DELETE_ORIGINAL.takeIf { saved },
        )
        entry.phase == GenericPhase.Removed -> listOf(if (saved) GenericAction.PUT_BACK else GenericAction.FORGET)
        entry.phase == GenericPhase.Unpatched -> listOf(GenericAction.FORGET)
        else -> emptyList()
    }
}

internal fun genericActionLabel(action: GenericAction, entry: GenericEntry): String = when (action) {
    GenericAction.CONTINUE -> "Continue"
    GenericAction.REFRESH -> "Refresh"
    GenericAction.PUT_BACK -> "Put the original back"
    GenericAction.REPORT -> "Report how it works"
    GenericAction.DELETE_ORIGINAL -> "Delete the saved original (${formatBytes(entry.savedOriginalBytes)})"
    GenericAction.FORGET -> "Remove from the Library"
}

internal const val GENERIC_UNCHECKED = "Can't be checked right now. Check your connection, then open the Library again."

fun genericStatusLine(entry: GenericEntry): String {
    val phase = entry.phase
    return when {
        phase == null -> GENERIC_UNCHECKED
        phase is GenericPhase.Attention -> phase.message
        entry.record.intent != null -> "Not finished — open to continue"
        phase == GenericPhase.Patched -> "Patched on this phone · ${entry.record.versionName}"
        phase == GenericPhase.Removed -> "Removed from this phone"
        phase == GenericPhase.Unpatched -> "Original installed"
        // The other steps only come with an intent.
        else -> "Not finished — open to continue"
    }
}

fun refreshHintText(label: String, hint: RefreshHint): String = when (hint) {
    RefreshHint.NewerRules -> "Newer ad rules are out. Refresh to get them."
    is RefreshHint.TokenExpiring ->
        "Refresh within ${hint.daysLeft} ${if (hint.daysLeft == 1) "day" else "days"} to keep $label working."
    RefreshHint.TokenExpired -> "$label has stopped working. Refresh to start it again."
    RefreshHint.TokenUnknown -> "Refresh $label to keep it working."
}

internal fun genericUpdateHint(label: String) =
    "To update $label, put the original back, update it in Play, and patch it again."

/** The refresh hint and the update hint go under a finished patch only. */
internal fun showsGenericHints(entry: GenericEntry): Boolean =
    entry.phase == GenericPhase.Patched && entry.record.intent == null

internal fun deleteOriginalDialogText(entry: GenericEntry): String {
    val label = entry.record.label
    return "This frees ${formatBytes(entry.savedOriginalBytes)}. Without it, the original of $label can't be put " +
        "back from the Library. You can still remove $label and install it again from Google Play."
}

internal fun forgetDialogTitle(label: String) = "Remove $label from the Library?"

/** What forgetting [entry] does to the phone: nothing to the app, and a saved original goes with the record. */
internal fun forgetDialogText(entry: GenericEntry): String {
    val label = entry.record.label
    return if (entry.phase == GenericPhase.Removed) {
        "$label is no longer on this phone, and nothing of it is saved."
    } else {
        "$label stays on your phone as it is." + if (entry.record.original.isNotEmpty()) {
            " Its saved original (${formatBytes(entry.savedOriginalBytes)}) is deleted too."
        } else {
            ""
        }
    }
}
