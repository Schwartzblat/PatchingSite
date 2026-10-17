package site.androidmod.store.ui.move

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.BuildVersion
import site.androidmod.store.move.GuideStep
import site.androidmod.store.move.MoveDirection
import site.androidmod.store.move.MovePhase
import site.androidmod.store.move.OFFICIAL_SIG
import site.androidmod.store.move.PREVIOUS_SIG
import site.androidmod.store.move.facts
import site.androidmod.store.move.guide
import site.androidmod.store.move.record
import site.androidmod.store.patch.PatchFailure
import site.androidmod.store.patch.PatchStage
import site.androidmod.store.patch.PatchState
import site.androidmod.store.patch.StoreUpdateRequired
import site.androidmod.store.patch.message
import site.androidmod.store.ui.patch.patchControlText
import site.androidmod.store.ui.patch.patchFailureOffersRetry
import site.androidmod.store.ui.patch.patchProgress
import java.io.File

class OnDeviceGuideTest {

    private fun build(version: String) = BuildVersion(version, "apk", "wa.apk", 1L, "2026-10-01")

    // --- which version the guide compares against ---

    @Test
    fun `on-device guide uses latest as newest`() {
        assertEquals("2.26.40.1", guideNewestVersion(true, listOf(build("2.26.39.74")), "2.26.40.1"))
        // Not loaded yet: unknown, never the server-built list.
        assertNull(guideNewestVersion(true, listOf(build("2.26.39.74")), null))
    }

    @Test
    fun `off-device guide keeps the server versions`() {
        assertEquals("2.26.39.74", guideNewestVersion(false, listOf(build("2.26.39.74"), build("2.26.39.70")), "2.26.40.1"))
        assertNull(guideNewestVersion(false, emptyList(), "2.26.40.1"))
    }

    // --- the latest check's failures ---

    @Test
    fun `latest problems are put in the guide's words`() {
        val update = latestProblem(StoreUpdateRequired(500))!!
        assertEquals("This app needs an update first. It's updating now.", update.message)
        assertTrue(update.storeUpdateRequired)

        val paywall = latestProblem(ApiException(402, "Payment required"))!!
        assertEquals("You need a subscription to get it.", paywall.message)
        assertTrue(paywall.needsSubscription)

        val other = latestProblem(ApiException(500, "Boom"))!!
        assertEquals("Boom", other.message)
        assertFalse(other.needsSubscription)
        assertFalse(other.storeUpdateRequired)

        assertNull(latestProblem(IllegalStateException("not ours")))
    }

    @Test
    fun `mid-move the guide starts no store update of its own`() {
        val update = latestProblem(StoreUpdateRequired(500), moveActive = true)!!
        assertTrue(update.storeUpdateRequired)
        // Nothing updates by itself, so "It's updating now" would be false; the button below does it.
        assertEquals("This app needs an update first. Tap Download WhatsApp to update it.", update.message)
        assertFalse(guideStartsSelfUpdate(update, moveActive = true, alreadyTried = false))

        val idle = latestProblem(StoreUpdateRequired(500), moveActive = false)!!
        assertEquals("This app needs an update first. It's updating now.", idle.message)
        assertTrue(guideStartsSelfUpdate(idle, moveActive = false, alreadyTried = false))
        assertFalse("once per visit", guideStartsSelfUpdate(idle, moveActive = false, alreadyTried = true))
        assertFalse(guideStartsSelfUpdate(latestProblem(ApiException(500, "Boom"))!!, moveActive = false, alreadyTried = false))
    }

    @Test
    fun `an update's Installed state does not stand in for the guide's download`() {
        // Left in the registry by an earlier Library update: the guide's step must offer its own build.
        assertNull(guidePatchState(PatchState.Installed))
        assertEquals("Download WhatsApp", patchControlText(guidePatchState(PatchState.Installed), ready = false))
        val running = PatchState.Running(PatchStage.Patching)
        assertEquals(running, guidePatchState(running))
        val failed = PatchState.Failed(PatchFailure.NETWORK, "x")
        assertEquals(failed, guidePatchState(failed))
        assertNull(guidePatchState(null))
    }

    // --- an unknown signing key (R9) ---

    private val previousInstalled = facts(signature = PREVIOUS_SIG)
    private val unknownInstalled = facts(signature = "d".repeat(64))

    @Test
    fun `with the key unknown only a previous build is offered a move`() {
        val unknown = guide(unknownInstalled, previous = false)
        assertTrue(unknown.phase is MovePhase.AtStep)

        val hidden = hideMoveWhileKeyUnknown(unknown, onDevice = true, keyKnown = false, previous = false)
        assertEquals(MovePhase.NothingToDo, hidden.phase)
        // Only the offer is hidden: the chat-loss guards on Install and Uninstall stay.
        assertEquals(unknown.officialAtRisk, hidden.officialAtRisk)
        assertEquals(unknown.oursAtRisk, hidden.oursAtRisk)
        assertTrue(hidden.officialAtRisk)

        val previous = guide(previousInstalled, previous = true)
        assertEquals(previous, hideMoveWhileKeyUnknown(previous, onDevice = true, keyKnown = false, previous = true))
    }

    @Test
    fun `the regular WhatsApp stays guarded while the key is unknown`() {
        val regular = guide(facts(signature = OFFICIAL_SIG), previous = false)
        assertTrue(regular.officialAtRisk)
        val hidden = hideMoveWhileKeyUnknown(regular, onDevice = true, keyKnown = false, previous = false)
        assertEquals(MovePhase.NothingToDo, hidden.phase)
        assertTrue(hidden.officialAtRisk)
    }

    @Test
    fun `a known key or an off-device WhatsApp is never hidden`() {
        val unknown = guide(unknownInstalled, previous = false)
        assertEquals(unknown, hideMoveWhileKeyUnknown(unknown, onDevice = true, keyKnown = true, previous = false))
        assertEquals(unknown, hideMoveWhileKeyUnknown(unknown, onDevice = false, keyKnown = false, previous = false))
    }

    @Test
    fun `a move under way is never hidden by an unknown key`() {
        val underWay = guide(facts(signature = "d".repeat(64), record = record()), previous = false)
        assertTrue(underWay.inProgress)
        assertEquals(underWay, hideMoveWhileKeyUnknown(underWay, onDevice = true, keyKnown = false, previous = false))
    }

    // --- Android 11 and 12 can't patch (I4) ---

    @Test
    fun `on android 12 the move into ours says it needs android 13`() {
        val regular = guide(facts(signature = OFFICIAL_SIG), previous = false)
        assertEquals(MoveDirection.TO_OURS, regular.direction)
        assertTrue(regular.phase is MovePhase.AtStep)

        val held = moveIntoOursOnOldAndroid(regular, onDevice = true, sdkInt = 32)

        assertEquals(MovePhase.AndroidTooOld, held.phase)
        // Only the offer goes: the chat-loss guards stay.
        assertTrue(held.officialAtRisk)
        assertEquals("Patching on this phone needs Android 13 or newer.", PatchFailure.ANDROID_TOO_OLD.message())
    }

    @Test
    fun `on android 12 a previous build is not offered the move either, but can go back`() {
        val previous = guide(previousInstalled, previous = true)
        assertEquals(MoveDirection.TO_OURS, previous.direction)
        assertEquals(MovePhase.AndroidTooOld, moveIntoOursOnOldAndroid(previous, onDevice = true, sdkInt = 31).phase)
        assertTrue("going back stays on offer", moveIntoOursOnOldAndroid(previous, onDevice = true, sdkInt = 31).offersReturn)

        val goingBack = guide(previousInstalled, previous = true, previousGoesBack = true)
        assertEquals(MoveDirection.TO_OFFICIAL, goingBack.direction)
        assertEquals(goingBack, moveIntoOursOnOldAndroid(goingBack, onDevice = true, sdkInt = 31))
    }

    @Test
    fun `not possible yet also reads as needing android 13 there`() {
        val tooNew = guide(facts(signature = OFFICIAL_SIG), previous = false, newest = "2.0.0")
        assertTrue(tooNew.phase is MovePhase.NotPossibleYet)
        assertEquals(MovePhase.AndroidTooOld, moveIntoOursOnOldAndroid(tooNew, onDevice = true, sdkInt = 30).phase)
    }

    @Test
    fun `android 13, off-device, or a move under way is left alone`() {
        val regular = guide(facts(signature = OFFICIAL_SIG), previous = false)
        assertEquals(regular, moveIntoOursOnOldAndroid(regular, onDevice = true, sdkInt = 33))
        assertEquals(regular, moveIntoOursOnOldAndroid(regular, onDevice = false, sdkInt = 30))

        val underWay = guide(facts(signature = OFFICIAL_SIG, record = record()), previous = false)
        assertTrue(underWay.inProgress)
        assertEquals(underWay, moveIntoOursOnOldAndroid(underWay, onDevice = true, sdkInt = 30))
    }

    @Test
    fun `going back from a previous build can be undone until something is saved`() {
        assertTrue(offersUndoGoingBack(previous = true, previousGoesBack = true, inProgress = false))
        assertFalse(offersUndoGoingBack(previous = true, previousGoesBack = true, inProgress = true))
        assertFalse(offersUndoGoingBack(previous = true, previousGoesBack = false, inProgress = false))
        assertFalse(offersUndoGoingBack(previous = false, previousGoesBack = true, inProgress = false))
    }

    // --- step titles ---

    @Test
    fun `a previous build is removed under its own name`() {
        assertEquals(
            "Remove your current patched WhatsApp",
            GuideStep.REMOVE_OLD.title(MoveDirection.TO_OURS, fromPreviousBuild = true),
        )
        assertEquals(
            "Remove your current patched WhatsApp",
            GuideStep.REMOVE_OLD.title(MoveDirection.TO_OFFICIAL, fromPreviousBuild = true),
        )
        assertEquals("Remove the old WhatsApp", GuideStep.REMOVE_OLD.title(MoveDirection.TO_OURS))
        assertEquals("Remove our WhatsApp", GuideStep.REMOVE_OLD.title(MoveDirection.TO_OFFICIAL))
        assertEquals("Download the new WhatsApp", GuideStep.DOWNLOAD.title(MoveDirection.TO_OURS, fromPreviousBuild = true))
    }

    // --- the patch control ---

    @Test
    fun `patch control text for each state`() {
        assertEquals("Download WhatsApp", patchControlText(null, ready = false))
        assertEquals("Checking version…", patchControlText(PatchState.Running(PatchStage.CheckingVersion), false))
        assertEquals(
            "Downloading WhatsApp 42%",
            patchControlText(PatchState.Running(PatchStage.Downloading(42, 100)), false),
        )
        assertEquals(
            "Downloading WhatsApp…",
            patchControlText(PatchState.Running(PatchStage.Downloading(42, -1)), false),
        )
        assertEquals("Patching…", patchControlText(PatchState.Running(PatchStage.Patching), false))
        assertEquals("Signing…", patchControlText(PatchState.Running(PatchStage.Signing), false))
        assertEquals("Waiting for your answer…", patchControlText(PatchState.AwaitingConfirmation(listOf("Status")), false))
        assertEquals("Ready ✓", patchControlText(PatchState.Ready(File("a.apk"), "2.26.40.1"), ready = true))
        assertEquals("Ready ✓", patchControlText(null, ready = true))
        // A Ready that lost its file is not ready.
        assertEquals("Download WhatsApp", patchControlText(PatchState.Ready(File("a.apk"), "2.26.40.1"), ready = false))
        assertEquals(
            "Nope",
            patchControlText(PatchState.Failed(PatchFailure.FAILED, "Nope"), ready = false),
        )
        // A running job outranks a build already on disk.
        assertEquals("Patching…", patchControlText(PatchState.Running(PatchStage.Patching), ready = true))
    }

    @Test
    fun `a failure that cannot change offers no retry`() {
        assertFalse(patchFailureOffersRetry(PatchFailure.ANDROID_TOO_OLD))
        assertTrue(patchFailureOffersRetry(PatchFailure.NEEDS_SUBSCRIPTION))
        assertTrue(patchFailureOffersRetry(PatchFailure.NETWORK))
        assertTrue(patchFailureOffersRetry(PatchFailure.FAILED))
    }

    @Test
    fun `patch progress is a fraction only while downloading`() {
        assertEquals(0.25f, patchProgress(PatchState.Running(PatchStage.Downloading(25, 100)))!!, 0.0001f)
        assertNull(patchProgress(PatchState.Running(PatchStage.Downloading(25, 0))))
        assertNull(patchProgress(PatchState.Running(PatchStage.Signing)))
        assertNull(patchProgress(null))
    }

}
