package site.androidmod.store.patch

import site.androidmod.store.download.NotificationText
import site.androidmod.store.install.InstalledApp

/** What the store has to offer for an installed on-device app. */
sealed interface UpdateOffer {
    data object None : UpdateOffer
    data class NewVersion(val versionName: String) : UpdateOffer

    /** Same WhatsApp version, newer patch; [featuresBack] are ids that were off and are now on. */
    data class PatchUpdate(val featuresBack: List<String>) : UpdateOffer

    /** Still on the old shared-key build: a one-time move to the user's own key. */
    data object NeedsMove : UpdateOffer
}

fun updateOffer(
    installed: InstalledApp?,
    patchInfo: PatchInfo?,
    latest: LatestPatch,
    userCertSha256: String?,
    sharedCertSha256: String?,
): UpdateOffer {
    if (installed == null) return UpdateOffer.None
    val signer = installed.signerSha256
    if (signer != null && signer == sharedCertSha256 && signer != userCertSha256) return UpdateOffer.NeedsMove
    // Not ours (the regular app): the guide handles that one.
    if (signer == null || signer != userCertSha256) return UpdateOffer.None
    return when {
        latest.versionCode > installed.versionCode -> UpdateOffer.NewVersion(latest.versionName)
        latest.versionCode == installed.versionCode && latest.revision > (patchInfo?.revision ?: 0) ->
            UpdateOffer.PatchUpdate(patchInfo?.featuresOff.orEmpty() - latest.featuresOff.toSet())
        else -> UpdateOffer.None
    }
}

/** Identifies one offer, so it is announced once. */
fun offerKey(pkg: String, latest: LatestPatch, offer: UpdateOffer): String =
    "$pkg:${latest.versionCode}:${latest.revision}:${offer::class.simpleName}"

/** The notification words for [offer]; null for None. [backTitles] are the visible titles of the features coming back. */
internal fun updateNotificationText(name: String, offer: UpdateOffer, backTitles: List<String>): NotificationText? =
    when (offer) {
        UpdateOffer.None -> null
        is UpdateOffer.NewVersion ->
            NotificationText("$name ${offer.versionName} is ready to update", "Tap to update. Your chats stay.")
        is UpdateOffer.PatchUpdate -> NotificationText(
            "Patch update for $name",
            if (backTitles.isEmpty()) "Tap to update." else "Brings back: ${backTitles.joinToString(", ")}",
        )
        UpdateOffer.NeedsMove ->
            NotificationText("Move $name to the new update system", "A one-time move. Your chats come along.")
    }
