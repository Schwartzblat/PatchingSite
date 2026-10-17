package site.androidmod.store.ui.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import site.androidmod.store.patch.PatchStage
import site.androidmod.store.patch.PatchState

class PendingQuestionTest {
    @Test fun noStatesMeansNoQuestion() {
        assertNull(pendingQuestion(emptyMap()))
    }

    @Test fun onlyAwaitingConfirmationAsks() {
        val states = mapOf(
            "a" to PatchState.Running(PatchStage.Patching),
            "b" to PatchState.Installed,
        )
        assertNull(pendingQuestion(states))
    }

    @Test fun askAboutTheAwaitingPackageWithItsTitles() {
        val states = mapOf(
            "a" to PatchState.Running(PatchStage.Patching),
            "b" to PatchState.AwaitingConfirmation(listOf("Status", "Channels")),
        )
        assertEquals("b" to listOf("Status", "Channels"), pendingQuestion(states))
    }

    @Test fun firstByPackageNameWhenSeveralAwait() {
        val states = linkedMapOf(
            "z.app" to PatchState.AwaitingConfirmation(listOf("Z")),
            "a.app" to PatchState.AwaitingConfirmation(listOf("A")),
        )
        assertEquals("a.app" to listOf("A"), pendingQuestion(states))
    }
}
