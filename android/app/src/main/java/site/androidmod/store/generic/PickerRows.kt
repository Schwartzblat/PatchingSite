package site.androidmod.store.generic

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import site.androidmod.patch.ApkFacts
import site.androidmod.patch.CantPatch as EngineCantPatch

/** An app installed on the phone, as the picker sees it. */
data class InstalledPackage(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    /** ApplicationInfo.sourceDir, then splitSourceDirs in order. */
    val apks: List<File>,
    /** Every apkContentsSigners certificate, as lowercase SHA-256 hex. */
    val signerSha256s: Set<String>,
)

sealed interface PickerBadge {
    data object Checking : PickerBadge
    data object Tested : PickerBadge
    data object NotTested : PickerBadge
    data object InOurStore : PickerBadge
    data object AlreadyPatched : PickerBadge
    data class CantPatch(val reasons: List<String>) : PickerBadge
}

data class PickerRow(val app: InstalledPackage, val badge: PickerBadge) {
    val pickable: Boolean get() = badge == PickerBadge.Tested || badge == PickerBadge.NotTested
}

sealed interface Inspection {
    data object Pending : Inspection
    data class Done(val facts: ApkFacts) : Inspection
    data object Unreadable : Inspection
}

/** Runs [inspect]; any failure except cancellation means the app's files couldn't be read. */
fun inspectSafely(inspect: () -> ApkFacts): Inspection = try {
    Inspection.Done(inspect())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Inspection.Unreadable
}

fun pickerBadge(
    app: InstalledPackage,
    inspection: Inspection,
    obb: Boolean?,
    storePackages: Set<String>,
    tested: Set<String>,
    sharedCerts: Set<String>,
    userCert: String?,
): PickerBadge {
    if (app.packageName in storePackages) return PickerBadge.InOurStore
    if (app.signerSha256s.any { it in sharedCerts || it == userCert }) return PickerBadge.AlreadyPatched
    if (inspection == Inspection.Pending) return PickerBadge.Checking
    val reasons = buildList {
        when (inspection) {
            Inspection.Unreadable -> add("Its files couldn't be read.")
            is Inspection.Done -> inspection.facts.cantPatch.forEach { add(cantPatchText(it)) }
            Inspection.Pending -> Unit
        }
        if (obb == true) add("It keeps extra data in Android/obb, which Android deletes with the app.")
    }
    if (reasons.isNotEmpty()) return PickerBadge.CantPatch(reasons)
    return if (app.packageName in tested) PickerBadge.Tested else PickerBadge.NotTested
}

fun cantPatchText(reason: EngineCantPatch): String = when (reason) {
    EngineCantPatch.PAIRIP -> "It uses Google's anti-tamper check (PairIP), so a patched copy won't start."
    EngineCantPatch.SHARED_USER_ID -> "It shares its data with other apps, so it can't be swapped on its own."
    EngineCantPatch.TARGET_SDK_TOO_OLD -> "It's built for an Android version this phone no longer installs."
    EngineCantPatch.ALREADY_PATCHED -> "It has already been patched."
    EngineCantPatch.NEEDS_TRANSLATION -> "It runs only through this phone's translation for another chip type."
    EngineCantPatch.UNSUPPORTED_ABI -> "It has no code for this phone's chip type."
    EngineCantPatch.IN_OUR_STORE -> "It's in our store."
}

/** Rows whose label or package contains [query] (ignoring case), sorted by label. */
fun filterRows(rows: List<PickerRow>, query: String): List<PickerRow> =
    rows.filter {
        it.app.label.contains(query, ignoreCase = true) || it.app.packageName.contains(query, ignoreCase = true)
    }.sortedBy { it.app.label.lowercase() }
