package site.androidmod.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ReserveSpaceTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun the_reservation_answers_when_it_can() {
        val filesDir = folder.newFolder("files")
        assertEquals(3L, reserveOrMeasure(File(filesDir, "patch/work"), 5L, filesDir) { _, _ -> 3L })
    }

    @Test
    fun without_storage_manager_the_space_is_measured_on_files_dir() {
        val filesDir = folder.newFolder("files")
        // A generic job reserves room in original/ before it creates it.
        val notYet = File(filesDir, "generic/com.example.app/original")
        val noVolume: (File, Long) -> Long = { _, _ -> throw IOException("No volume for the path") }

        assertEquals(0L, reserveOrMeasure(notYet, 1024L, filesDir, noVolume))
        assertFalse(notYet.exists())
        // More than the volume holds is still short.
        assertTrue(reserveOrMeasure(notYet, Long.MAX_VALUE / 2, filesDir, noVolume) > 0)
    }
}
