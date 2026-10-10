package site.androidmod.store.move

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Step 4's park can start while the store is still copying the backup (it copies
 * on every open). It has to wait for the copy, not fail with the engine's
 * "Another operation is already running".
 */
class OneAtATimeTest {

    @Test
    fun `a second write waits for the first instead of failing`() {
        val gate = OneAtATime()
        val copying = CountDownLatch(1)
        val finishCopy = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())

        val copy = thread { gate.run { copying.countDown(); finishCopy.await(); order += "copy" } }
        copying.await()
        val park = thread { gate.run { order += "park" } }
        Thread.sleep(100)
        // The park has had time to run, and must not have.
        assertEquals(emptyList<String>(), order.toList())

        finishCopy.countDown()
        copy.join()
        park.join()
        assertEquals(listOf("copy", "park"), order.toList())
    }
}
