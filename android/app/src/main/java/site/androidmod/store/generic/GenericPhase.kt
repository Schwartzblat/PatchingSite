package site.androidmod.store.generic

/** What the phone has for the package right now. */
data class InstalledState(val signers: Set<String>, val versionCode: Long, val baseSha256: String)

sealed interface GenericPhase {
    /** The job hasn't finished: save the original, then build. */
    data object SaveAndBuild : GenericPhase
    data class RemoveOriginal(val askedBefore: Boolean) : GenericPhase
    data object InstallPatched : GenericPhase

    /** The patched copy is installed; with an intent, finish it. */
    data object Patched : GenericPhase
    data class RemovePatched(val askedBefore: Boolean) : GenericPhase
    data object InstallOriginal : GenericPhase
    data object Restored : GenericPhase

    /** Refresh: install over the patched copy. */
    data object InstallUpdate : GenericPhase

    /** No intent, and the app isn't installed. */
    data object Removed : GenericPhase

    /** No intent, and the original signer is installed. */
    data object Unpatched : GenericPhase
    data class Attention(val message: String) : GenericPhase
}

/** The step a flow is at, from the record's facts and what is installed. Pure. */
fun genericPhase(record: GenericRecord, installed: InstalledState?, userCert: String?): GenericPhase {
    val ours = installed != null && userCert != null && userCert in installed.signers
    val original = installed != null && installed.signers == record.originalSigners
    val ourBuild = installed != null && record.built.isNotEmpty() && installed.baseSha256 == record.built.first().sha256
    val asked = record.uninstallAskedAt != null
    val changed = GenericPhase.Attention("${record.label} was changed outside the store. Uninstall it, then put the original back from the Library.")

    return when (record.intent) {
        GenericIntent.PATCH -> when {
            installed == null && record.original.isEmpty() ->
                GenericPhase.Attention("${record.label} was removed before its original was saved.")
            record.original.isEmpty() || record.built.isEmpty() -> GenericPhase.SaveAndBuild
            original && installed.versionCode == record.versionCode -> GenericPhase.RemoveOriginal(asked)
            installed == null -> GenericPhase.InstallPatched
            ourBuild -> GenericPhase.Patched
            else -> changed
        }
        GenericIntent.RESTORE -> when {
            record.original.isEmpty() ->
                GenericPhase.Attention("${record.label}'s saved original is missing, so it can't be put back.")
            ours -> GenericPhase.RemovePatched(asked)
            installed == null -> GenericPhase.InstallOriginal
            original -> GenericPhase.Restored
            else -> changed
        }
        GenericIntent.REFRESH -> when {
            record.built.isEmpty() -> GenericPhase.SaveAndBuild
            ourBuild -> GenericPhase.Patched
            ours -> GenericPhase.InstallUpdate
            else -> changed
        }
        null -> when {
            installed == null -> GenericPhase.Removed
            ours -> GenericPhase.Patched
            original -> GenericPhase.Unpatched
            else -> changed
        }
    }
}
