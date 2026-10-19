package site.androidmod.store.generic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GenericRecordsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun record(pkg: String = "com.example.app") = GenericRecord(
        packageName = pkg, label = "Example", versionName = "1.0", versionCode = 7L,
        originalSigners = setOf("aa"), apkNames = listOf("base.apk", "split_config.apk"),
        original = listOf(SavedApk("base.apk", 10, "ab")), intent = GenericIntent.PATCH, uninstallAskedAt = 5L,
    )

    @Test
    fun put_get_round_trip() {
        val records = GenericRecords(tmp.newFolder("generic"))
        val r = record()
        records.put(r)
        assertEquals(r, records.get("com.example.app"))
        assertEquals(listOf(r), records.all())
        assertNull(records.get("com.other.app"))
    }

    @Test
    fun corrupt_record_is_skipped_and_left_on_disk() {
        val root = tmp.newFolder("generic")
        val records = GenericRecords(root)
        records.put(record("com.good.app"))
        val bad = File(root, "com.bad.app/record.json").also { it.parentFile!!.mkdirs(); it.writeText("{not json") }
        assertEquals(listOf("com.good.app"), records.all().map { it.packageName })
        assertNull(records.get("com.bad.app"))
        assertTrue(bad.exists())
        assertEquals("{not json", bad.readText())
    }

    @Test
    fun remove_deletes_only_that_package() {
        val root = tmp.newFolder("generic")
        val records = GenericRecords(root)
        records.put(record("com.example.app"))
        records.put(record("com.example.app2"))
        File(records.originalDir("com.example.app"), "base.apk").also { it.parentFile!!.mkdirs(); it.writeText("x") }
        records.remove("com.example.app")
        assertFalse(File(root, "com.example.app").exists())
        assertTrue(File(root, "com.example.app2/record.json").exists())
        assertEquals(listOf("com.example.app2"), records.all().map { it.packageName })
    }

    @Test
    fun bad_package_names_are_refused() {
        val records = GenericRecords(tmp.newFolder("generic"))
        for (name in listOf("", "..", "../x", "a/b", "com/example.app", "com.example/../x", "single", "com..x", "1com.x", "com.example.")) {
            assertThrows(name, IllegalArgumentException::class.java) { records.get(name) }
            assertThrows(name, IllegalArgumentException::class.java) { records.remove(name) }
            assertThrows(name, IllegalArgumentException::class.java) { records.originalDir(name) }
            assertThrows(name, IllegalArgumentException::class.java) { records.put(record(name)) }
        }
    }

    @Test
    fun directories_live_under_the_package() {
        val root = tmp.newFolder("generic")
        val records = GenericRecords(root)
        assertEquals(File(root, "com.example.app/original"), records.originalDir("com.example.app"))
        assertEquals(File(root, "com.example.app/built"), records.builtDir("com.example.app"))
        assertEquals(File(root, "com.example.app/built.tmp"), records.builtTmpDir("com.example.app"))
    }

    @Test
    fun unknown_json_keys_are_ignored() {
        val root = tmp.newFolder("generic")
        File(root, "com.example.app").mkdirs()
        File(root, "com.example.app/record.json").writeText(
            """{"package":"com.example.app","label":"E","version_name":"1","version_code":1,"original_signers":["aa"],"apk_names":["base.apk"],"future":{"x":1}}""",
        )
        val r = GenericRecords(root).get("com.example.app")!!
        assertEquals("E", r.label)
        assertEquals(setOf("aa"), r.originalSigners)
        assertNull(r.intent)
    }
}
