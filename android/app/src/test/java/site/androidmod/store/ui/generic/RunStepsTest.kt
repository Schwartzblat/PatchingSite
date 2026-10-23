package site.androidmod.store.ui.generic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import site.androidmod.patch.Capability
import site.androidmod.store.generic.GenericIntent
import site.androidmod.store.generic.GenericPhase
import site.androidmod.store.patch.PatchFailure
import site.androidmod.store.patch.PatchStage
import site.androidmod.store.patch.PatchState
import site.androidmod.store.ui.generic.StepStatus.ACTIVE
import site.androidmod.store.ui.generic.StepStatus.DONE
import site.androidmod.store.ui.generic.StepStatus.FAILED
import site.androidmod.store.ui.generic.StepStatus.TODO

class RunStepsTest {

    private val patchTitles = listOf("Save the original", "Remove the ads", "Remove Example", "Install the patched copy")
    private val restoreTitles = listOf("Remove the patched copy", "Put the original back")
    private val refreshTitles = listOf("Remove the ads", "Install the update")

    private val everyPhase = listOf(
        GenericPhase.SaveAndBuild,
        GenericPhase.RemoveOriginal(askedBefore = false),
        GenericPhase.RemoveOriginal(askedBefore = true),
        GenericPhase.InstallPatched,
        GenericPhase.Patched,
        GenericPhase.RemovePatched(askedBefore = false),
        GenericPhase.RemovePatched(askedBefore = true),
        GenericPhase.InstallOriginal,
        GenericPhase.Restored,
        GenericPhase.InstallUpdate,
        GenericPhase.Removed,
        GenericPhase.Unpatched,
        GenericPhase.Attention("Example was changed outside the store. Put the original back, or contact us."),
    )

    private val failed = PatchState.Failed(PatchFailure.NETWORK, "Network error. Check your connection and try again.")

    private fun steps(
        intent: GenericIntent,
        phase: GenericPhase,
        state: PatchState? = null,
        originalSaved: Boolean = false,
        capabilities: Set<Capability> = setOf(Capability.ADS),
    ) = runSteps("Example", intent, phase, state, originalSaved, capabilities)

    private fun statuses(intent: GenericIntent, phase: GenericPhase, state: PatchState? = null, originalSaved: Boolean = false) =
        steps(intent, phase, state, originalSaved).map { it.status }

    @Test
    fun every_intent_and_phase_has_the_intents_titles() {
        val titles = mapOf(
            GenericIntent.PATCH to patchTitles,
            GenericIntent.RESTORE to restoreTitles,
            GenericIntent.REFRESH to refreshTitles,
        )
        for ((intent, expected) in titles) {
            for (phase in everyPhase) {
                assertEquals("$intent $phase", expected, steps(intent, phase).map { it.title })
            }
        }
    }

    @Test
    fun patch_steps_follow_the_phase() {
        val expected = mapOf(
            GenericPhase.SaveAndBuild to listOf(ACTIVE, TODO, TODO, TODO),
            GenericPhase.RemoveOriginal(askedBefore = false) to listOf(DONE, DONE, ACTIVE, TODO),
            GenericPhase.RemoveOriginal(askedBefore = true) to listOf(DONE, DONE, ACTIVE, TODO),
            GenericPhase.InstallPatched to listOf(DONE, DONE, DONE, ACTIVE),
            GenericPhase.Patched to listOf(DONE, DONE, DONE, DONE),
        )
        for (phase in everyPhase) {
            assertEquals("$phase", expected[phase] ?: List(4) { TODO }, statuses(GenericIntent.PATCH, phase))
        }
    }

    @Test
    fun patch_building_is_the_ad_removal_step() {
        val running = { stage: PatchStage -> PatchState.Running(stage) }
        val save = listOf(ACTIVE, TODO, TODO, TODO)
        val build = listOf(DONE, ACTIVE, TODO, TODO)
        assertEquals(save, statuses(GenericIntent.PATCH, GenericPhase.SaveAndBuild, running(PatchStage.CheckingVersion)))
        assertEquals(save, statuses(GenericIntent.PATCH, GenericPhase.SaveAndBuild, running(PatchStage.SavingOriginal(1, 2))))
        assertEquals(build, statuses(GenericIntent.PATCH, GenericPhase.SaveAndBuild, running(PatchStage.Patching)))
        assertEquals(build, statuses(GenericIntent.PATCH, GenericPhase.SaveAndBuild, running(PatchStage.Signing)))
        // Resumed with the original already saved: building is all that is left.
        assertEquals(build, statuses(GenericIntent.PATCH, GenericPhase.SaveAndBuild, running(PatchStage.CheckingVersion), originalSaved = true))
        assertEquals(build, statuses(GenericIntent.PATCH, GenericPhase.SaveAndBuild, originalSaved = true))
    }

    @Test
    fun restore_steps_follow_the_phase() {
        val expected = mapOf(
            GenericPhase.RemovePatched(askedBefore = false) to listOf(ACTIVE, TODO),
            GenericPhase.RemovePatched(askedBefore = true) to listOf(ACTIVE, TODO),
            GenericPhase.InstallOriginal to listOf(DONE, ACTIVE),
            GenericPhase.Restored to listOf(DONE, DONE),
        )
        for (phase in everyPhase) {
            assertEquals("$phase", expected[phase] ?: List(2) { TODO }, statuses(GenericIntent.RESTORE, phase))
        }
    }

    @Test
    fun refresh_steps_follow_the_phase() {
        val expected = mapOf(
            GenericPhase.SaveAndBuild to listOf(ACTIVE, TODO),
            GenericPhase.InstallUpdate to listOf(DONE, ACTIVE),
            GenericPhase.Patched to listOf(DONE, DONE),
        )
        for (phase in everyPhase) {
            assertEquals("$phase", expected[phase] ?: List(2) { TODO }, statuses(GenericIntent.REFRESH, phase))
        }
    }

    @Test
    fun a_failed_job_marks_the_active_step_failed() {
        assertEquals(listOf(FAILED, TODO, TODO, TODO), statuses(GenericIntent.PATCH, GenericPhase.SaveAndBuild, failed))
        assertEquals(
            listOf(DONE, FAILED, TODO, TODO),
            statuses(GenericIntent.PATCH, GenericPhase.SaveAndBuild, failed, originalSaved = true),
        )
        assertEquals(listOf(FAILED, TODO), statuses(GenericIntent.REFRESH, GenericPhase.SaveAndBuild, failed))
    }

    @Test
    fun runLabelMatchesTheMix() {
        val ads = setOf(Capability.ADS)
        val iap = setOf(Capability.IAP)
        val both = setOf(Capability.ADS, Capability.IAP)
        assertEquals(patchTitles, steps(GenericIntent.PATCH, GenericPhase.SaveAndBuild, capabilities = ads).map { it.title })
        assertEquals(
            listOf("Save the original", "Bypass in-app purchases", "Remove Example", "Install the patched copy"),
            steps(GenericIntent.PATCH, GenericPhase.SaveAndBuild, capabilities = iap).map { it.title },
        )
        assertEquals(
            listOf("Save the original", "Remove the ads and bypass purchases", "Remove Example", "Install the patched copy"),
            steps(GenericIntent.PATCH, GenericPhase.SaveAndBuild, capabilities = both).map { it.title },
        )
        assertEquals(
            listOf("Bypass in-app purchases", "Install the update"),
            steps(GenericIntent.REFRESH, GenericPhase.SaveAndBuild, capabilities = iap).map { it.title },
        )
        assertEquals(
            listOf("Remove the ads and bypass purchases", "Install the update"),
            steps(GenericIntent.REFRESH, GenericPhase.SaveAndBuild, capabilities = both).map { it.title },
        )
        // The build step keeps its place, whatever it is called.
        assertEquals(
            listOf(DONE, ACTIVE, TODO, TODO),
            steps(GenericIntent.PATCH, GenericPhase.SaveAndBuild, originalSaved = true, capabilities = iap).map { it.status },
        )
    }

    @Test
    fun try_again_is_offered_only_where_it_can_help() {
        assertTrue(jobOffersRetry(PatchState.Failed(PatchFailure.NETWORK, "Network error. Check your connection and try again.")))
        assertTrue(jobOffersRetry(PatchState.Failed(PatchFailure.NEEDS_SUBSCRIPTION, "You need an active plan to patch apps.")))
        assertTrue(jobOffersRetry(PatchState.Failed(PatchFailure.FAILED, "Patching failed. Nothing was removed.")))
        // The phone can't patch at all.
        assertFalse(jobOffersRetry(PatchState.Failed(PatchFailure.ANDROID_TOO_OLD, "Patching apps needs Android 13 or newer.")))
        // The same refusal again, until the customer changes something.
        assertFalse(
            jobOffersRetry(
                PatchState.Failed(PatchFailure.FAILED, "The saved original of Example is damaged. Stop, then pick Example again.", retryHelps = false),
            ),
        )
    }

    @Test
    fun only_the_jobs_own_step_can_fail() {
        // The job builds; a failure it left behind says nothing about removing or installing.
        assertEquals(listOf(DONE, DONE, ACTIVE, TODO), statuses(GenericIntent.PATCH, GenericPhase.RemoveOriginal(false), failed))
        assertEquals(listOf(DONE, DONE, DONE, ACTIVE), statuses(GenericIntent.PATCH, GenericPhase.InstallPatched, failed))
        assertEquals(listOf(DONE, ACTIVE), statuses(GenericIntent.REFRESH, GenericPhase.InstallUpdate, failed))
        assertEquals(listOf(DONE, ACTIVE), statuses(GenericIntent.RESTORE, GenericPhase.InstallOriginal, failed))
        // A ready build is not a failure.
        val ready = PatchState.Ready(File("/files/generic/com.example.app/built"), "1.0")
        assertEquals(listOf(DONE, DONE, ACTIVE, TODO), statuses(GenericIntent.PATCH, GenericPhase.RemoveOriginal(false), ready))
    }
}
