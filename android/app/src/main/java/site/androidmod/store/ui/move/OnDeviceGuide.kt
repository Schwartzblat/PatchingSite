package site.androidmod.store.ui.move

import site.androidmod.store.data.ApiException
import site.androidmod.store.data.BuildVersion
import site.androidmod.store.data.needsSubscription
import site.androidmod.store.move.MoveGuide
import site.androidmod.store.move.MovePhase
import site.androidmod.store.patch.PatchFailure
import site.androidmod.store.patch.StoreUpdateRequired
import site.androidmod.store.patch.message

/*
 * The pure decisions of the guide once WhatsApp is patched on the phone, kept out of
 * MoveViewModel and the screens so they can be tested without either.
 */

/**
 * The version "Not possible yet" compares the installed WhatsApp against. Off the phone
 * that is the newest server build; on it the server's latest ([latestVersion], from
 * PatchApi.latest), which is null until it has loaded - never a leftover server build.
 */
fun guideNewestVersion(onDevice: Boolean, versions: List<BuildVersion>, latestVersion: String?): String? =
    if (onDevice) latestVersion else versions.firstOrNull()?.version

/**
 * With the user's signing key unknown (not cached, and the fetch failed or timed out) the
 * store cannot tell the user's own build from the regular WhatsApp: both look like
 * "someone else's". Offering a move then could prompt a needless one after a store
 * reinstall, so the guide offers none: [MovePhase.NothingToDo]. Only the offer goes: the
 * at-risk flags are the chat-loss guards on Install and Uninstall and stay as they are.
 * A previous build is told by the store's own certificate, which needs no key, and a move
 * already under way keeps going. Attention and RemovedBeforeSaved are about the phone's
 * state, not about which build it is, so they stay.
 */
fun hideMoveWhileKeyUnknown(guide: MoveGuide, onDevice: Boolean, keyKnown: Boolean, previous: Boolean): MoveGuide {
    if (!onDevice || keyKnown || previous || guide.inProgress) return guide
    if (guide.phase !is MovePhase.AtStep && guide.phase !is MovePhase.NotPossibleYet) return guide
    return guide.copy(phase = MovePhase.NothingToDo)
}

/**
 * "Move to the new update system instead": a previous build's user who chose to go back
 * (MoveViewModel.chooseGoingBack) may change their mind until something is saved.
 */
fun offersUndoGoingBack(previous: Boolean, previousGoesBack: Boolean, inProgress: Boolean): Boolean =
    previous && previousGoesBack && !inProgress

/** Why PatchApi.latest failed, in the guide's words. */
data class LatestProblem(val message: String, val needsSubscription: Boolean, val storeUpdateRequired: Boolean)

/** Null for anything that is not one of the server's answers; the caller lets that propagate. */
fun latestProblem(error: Throwable): LatestProblem? = when {
    error is StoreUpdateRequired ->
        LatestProblem(PatchFailure.STORE_UPDATE_REQUIRED.message(), needsSubscription = false, storeUpdateRequired = true)
    error is ApiException && error.needsSubscription ->
        LatestProblem(PatchFailure.NEEDS_SUBSCRIPTION.message(), needsSubscription = true, storeUpdateRequired = false)
    error is ApiException -> LatestProblem(error.message, needsSubscription = false, storeUpdateRequired = false)
    else -> null
}
