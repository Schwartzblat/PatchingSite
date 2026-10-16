package site.androidmod.store.patch

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatchRegistryTest {

    @Before
    fun setUp() = PatchRegistry.reset()

    @After
    fun tearDown() = PatchRegistry.reset()

    @Test
    fun `answer resumes the waiting job`() = runTest {
        val answer = async { PatchRegistry.awaitAnswer("a.b") { PatchRegistry.update("a.b", PatchState.AwaitingConfirmation(listOf("X"))) } }
        runCurrent()
        assertEquals(PatchState.AwaitingConfirmation(listOf("X")), PatchRegistry.states.value["a.b"])
        assertFalse(answer.isCompleted)

        PatchRegistry.answer("a.b", true)

        assertTrue(answer.await())
    }

    @Test
    fun `a no answer resumes it with false`() = runTest {
        val answer = async { PatchRegistry.awaitAnswer("a.b") }
        runCurrent()

        PatchRegistry.answer("a.b", false)

        assertFalse(answer.await())
    }

    @Test
    fun `an answer for another package changes nothing`() = runTest {
        val answer = async { PatchRegistry.awaitAnswer("a.b") }
        runCurrent()

        PatchRegistry.answer("c.d", true)
        runCurrent()

        assertFalse(answer.isCompleted)
        answer.cancel()
    }

    @Test
    fun `clearing the package dismisses the wait`() = runTest {
        val answer = async { PatchRegistry.awaitAnswer("a.b") }
        runCurrent()

        PatchRegistry.clear("a.b")

        assertFalse(answer.await())
    }

    @Test
    fun `a cancelled wait returns false instead of throwing`() = runTest {
        var result: Boolean? = null
        val job = launch { result = PatchRegistry.awaitAnswer("a.b") }
        runCurrent()

        job.cancel()
        runCurrent()

        assertEquals(false, result)
        // The cancelled wait left nothing behind that a late answer could trip over.
        PatchRegistry.answer("a.b", true)
    }

    @Test
    fun `an unanswered wait gives up with false after its limit`() = runTest {
        val answer = async { PatchRegistry.awaitAnswer("a.b", timeoutMillis = 1_000) }
        runCurrent()

        advanceTimeBy(999)
        runCurrent()
        assertFalse(answer.isCompleted)

        advanceTimeBy(2)
        runCurrent()
        assertTrue(answer.isCompleted)
        assertFalse(answer.await())
        // Nothing left waiting: a late answer is ignored.
        PatchRegistry.answer("a.b", true)
    }

    @Test
    fun `an answer inside the limit wins`() = runTest {
        val answer = async { PatchRegistry.awaitAnswer("a.b", timeoutMillis = 1_000) }
        runCurrent()
        advanceTimeBy(500)

        PatchRegistry.answer("a.b", true)

        assertTrue(answer.await())
    }

    @Test
    fun `clear forgets a package`() {
        PatchRegistry.update("a.b", PatchState.Running(PatchStage.Patching))
        PatchRegistry.update("c.d", PatchState.Installed)

        PatchRegistry.clear("a.b")

        assertEquals(setOf("c.d"), PatchRegistry.states.value.keys)
    }

    @Test
    fun `busy while running, waiting or installing`() {
        assertFalse(PatchRegistry.isBusy("a.b"))
        PatchRegistry.update("a.b", PatchState.Running(PatchStage.Signing))
        assertTrue(PatchRegistry.isBusy("a.b"))
        PatchRegistry.update("a.b", PatchState.AwaitingConfirmation(emptyList()))
        assertTrue(PatchRegistry.isBusy("a.b"))
        PatchRegistry.update("a.b", PatchState.Installing)
        assertTrue(PatchRegistry.isBusy("a.b"))
        PatchRegistry.update("a.b", PatchState.Installed)
        assertFalse(PatchRegistry.isBusy("a.b"))
        PatchRegistry.update("a.b", PatchState.Failed(PatchFailure.FAILED, "x"))
        assertFalse(PatchRegistry.isBusy("a.b"))
    }
}
