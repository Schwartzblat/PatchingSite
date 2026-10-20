package site.androidmod.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainProcessTest {

    @Test
    fun only_the_main_process_sets_up_the_store() {
        assertTrue(isMainProcess("site.androidmod.store", "site.androidmod.store"))
        // The OBB probe's process: setting up the store there would wipe a running job's work.
        assertFalse(isMainProcess("site.androidmod.store:obbprobe", "site.androidmod.store"))
        assertFalse(isMainProcess("site.androidmod.store.other", "site.androidmod.store"))
    }

    @Test
    fun an_unknown_process_name_counts_as_the_main_process() {
        // Android names the process before Application.onCreate; a store that can't start would be worse.
        assertTrue(isMainProcess(null, "site.androidmod.store"))
    }
}
