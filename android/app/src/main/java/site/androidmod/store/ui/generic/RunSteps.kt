package site.androidmod.store.ui.generic

import site.androidmod.store.generic.GenericIntent
import site.androidmod.store.generic.GenericPhase
import site.androidmod.store.patch.PatchStage
import site.androidmod.store.patch.PatchState

enum class StepStatus { TODO, ACTIVE, DONE, FAILED }

data class RunStep(val title: String, val status: StepStatus)

/**
 * The run screen's steps for [intent], at [phase]. The step [phase] is at is ACTIVE, every step
 * before it DONE; a finished flow (Patched, or Restored) has every step DONE, and a phase outside
 * the intent's steps (Attention, say) none started. [patchState] is the job's: while it builds,
 * a PATCH is past saving the original, which [originalSaved] (the record lists the copies) also
 * says; a Failed job marks its step FAILED. Only the job's step can fail here: a removal or an
 * install is the screen's own, and says so there. Pure.
 */
fun runSteps(
    label: String,
    intent: GenericIntent,
    phase: GenericPhase,
    patchState: PatchState?,
    originalSaved: Boolean = false,
): List<RunStep> {
    val titles = when (intent) {
        GenericIntent.PATCH -> listOf("Save the original", "Remove the ads", "Remove $label", "Install the patched copy")
        GenericIntent.RESTORE -> listOf("Remove the patched copy", "Put the original back")
        GenericIntent.REFRESH -> listOf("Remove the ads", "Install the update")
    }
    val building = (patchState as? PatchState.Running)?.stage.let { it == PatchStage.Patching || it == PatchStage.Signing }
    // The index of the step under way; titles.size when every step is done, null when none has started.
    val current = when (intent) {
        GenericIntent.PATCH -> when (phase) {
            GenericPhase.SaveAndBuild -> if (originalSaved || building) 1 else 0
            is GenericPhase.RemoveOriginal -> 2
            GenericPhase.InstallPatched -> 3
            GenericPhase.Patched -> titles.size
            else -> null
        }
        GenericIntent.RESTORE -> when (phase) {
            is GenericPhase.RemovePatched -> 0
            GenericPhase.InstallOriginal -> 1
            GenericPhase.Restored -> titles.size
            else -> null
        }
        GenericIntent.REFRESH -> when (phase) {
            GenericPhase.SaveAndBuild -> 0
            GenericPhase.InstallUpdate -> 1
            GenericPhase.Patched -> titles.size
            else -> null
        }
    }
    val failed = phase == GenericPhase.SaveAndBuild && patchState is PatchState.Failed
    return titles.mapIndexed { index, title ->
        val status = when {
            current == null || index > current -> StepStatus.TODO
            index < current -> StepStatus.DONE
            failed -> StepStatus.FAILED
            else -> StepStatus.ACTIVE
        }
        RunStep(title, status)
    }
}
