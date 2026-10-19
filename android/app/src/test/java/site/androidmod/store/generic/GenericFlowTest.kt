package site.androidmod.store.generic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import site.androidmod.patch.GenericPatchInfo
import site.androidmod.store.install.InstallFailure
import site.androidmod.store.install.InstallOutcome
import java.io.File
import java.security.MessageDigest

class GenericFlowTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Real record files; the phone's installed state, the job's busy flag and the installer are fakes. */
    private inner class Fixture(root: File) {
        val recordsRoot = File(root, "files/generic")
        val records = GenericRecords(recordsRoot)
        val packageDir = File(recordsRoot, PKG)
        val originalDir = records.originalDir(PKG)
        val builtDir = records.builtDir(PKG)

        // The app as Android installed it.
        val appDir = File(root, "data/app/$PKG")
        val base = File(appDir, BASE).also { it.parentFile!!.mkdirs(); it.writeBytes(BASE_BYTES) }
        val split = File(appDir, SPLIT).also { it.writeBytes(SPLIT_BYTES) }

        /** What the phone has for the package; the flow reads it at every call. */
        var installed: InstalledState? = ORIGINAL_INSTALLED
        var busy = false

        /** The user's cached signing key; null when it isn't cached. */
        var cert: String? = USER_CERT
        var clock = 1_000L
        var installAnswer: InstallOutcome = InstallOutcome.Success

        /** Runs inside the installer, before it answers. */
        var duringInstall: suspend () -> Unit = {}
        val installs = mutableListOf<Pair<List<File>, Set<String>>>()

        val flow = GenericFlow(
            records = records,
            installedState = { pkg ->
                assertEquals(PKG, pkg)
                installed
            },
            userCert = { cert },
            install = { apks, extraTrusted ->
                installs += apks to extraTrusted
                duringInstall()
                installAnswer
            },
            sha256 = ::sha256Of,
            now = { clock },
            isBusy = { pkg ->
                assertEquals(PKG, pkg)
                busy
            },
        )

        fun app(signers: Set<String> = setOf(DEV_CERT)) =
            InstalledPackage(PKG, LABEL, VERSION_NAME, VERSION_CODE, listOf(base, split), signers)

        fun record(): GenericRecord = records.get(PKG)!!

        /** A begun patch after its job: the original saved and the patched copy built, both verified. */
        fun built(): GenericRecord {
            flow.begin(app())
            val record = record().copy(
                original = save(originalDir, BASE to BASE_BYTES, SPLIT to SPLIT_BYTES),
                built = save(builtDir, BASE to BUILT_BASE, SPLIT to BUILT_SPLIT),
                builtBundleId = BUNDLE_ID,
                builtTokenExpiresAt = EXPIRES_AT,
            )
            records.put(record)
            return record
        }

        /** A finished patch: our copy installed, its original saved, no intent. */
        fun patched(): GenericRecord {
            val record = GenericRecord(
                packageName = PKG,
                label = LABEL,
                versionName = VERSION_NAME,
                versionCode = VERSION_CODE,
                originalSigners = setOf(DEV_CERT),
                apkNames = listOf(BASE, SPLIT),
                original = save(originalDir, BASE to BASE_BYTES, SPLIT to SPLIT_BYTES),
                patchedBundleId = OLD_BUNDLE_ID,
                tokenExpiresAt = OLD_EXPIRES_AT,
                patchedAt = 500L,
            )
            records.put(record)
            installed = PATCHED_INSTALLED
            return record
        }

        /** What a Refresh's job leaves: a new build of our copy. */
        fun refreshBuilt() {
            records.put(
                record().copy(
                    built = save(builtDir, BASE to UPDATE_BASE, SPLIT to UPDATE_SPLIT),
                    builtBundleId = NEW_BUNDLE_ID,
                    builtTokenExpiresAt = NEW_EXPIRES_AT,
                ),
            )
        }

        fun files(dir: File): List<String> =
            if (!dir.exists()) emptyList() else dir.walk().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()
    }

    private fun fixture() = Fixture(folder.newFolder())

    private fun save(dir: File, vararg files: Pair<String, ByteArray>): List<SavedApk> {
        dir.mkdirs()
        return files.map { (name, bytes) ->
            File(dir, name).writeBytes(bytes)
            SavedApk(name, bytes.size.toLong(), sha256(bytes))
        }
    }

    /** The same size, one byte different. */
    private fun damage(file: File) {
        val bytes = file.readBytes()
        bytes[0] = (bytes[0] + 1).toByte()
        file.writeBytes(bytes)
    }

    private fun refused(message: String) = InstallOutcome.Failure(message, InstallFailure.UNKNOWN)

    @Test
    fun begin_refuses_second_flow_for_same_app() {
        val f = fixture()
        val record = f.flow.begin(f.app())
        assertEquals(
            GenericRecord(
                packageName = PKG,
                label = LABEL,
                versionName = VERSION_NAME,
                versionCode = VERSION_CODE,
                originalSigners = setOf(DEV_CERT),
                apkNames = listOf(BASE, SPLIT),
                intent = GenericIntent.PATCH,
            ),
            record,
        )
        assertEquals(record, f.record())
        assertEquals(GenericPhase.SaveAndBuild, f.flow.phase(PKG))

        assertThrows(IllegalStateException::class.java) { f.flow.begin(f.app()) }
        assertEquals(record, f.record())

        // Nor while an original is being put back.
        val other = fixture()
        other.patched()
        assertNull(other.flow.startRestore(PKG))
        val restoring = other.record()
        assertThrows(IllegalStateException::class.java) { other.flow.begin(other.app()) }
        assertEquals(restoring, other.record())
    }

    @Test
    fun begin_after_a_finished_swap_keeps_a_saved_original_still_needed() {
        val f = fixture()
        val patched = f.patched()
        // Our patched copy is installed: a new patch would overwrite the only copy of the original.
        assertThrows(IllegalStateException::class.java) { f.flow.begin(f.app(setOf(USER_CERT))) }
        // The phone decides, not the caller's snapshot of the app.
        assertThrows(IllegalStateException::class.java) { f.flow.begin(f.app()) }
        f.installed = null
        assertThrows(IllegalStateException::class.java) { f.flow.begin(f.app()) }
        assertEquals(patched, f.record())
        assertEquals(listOf(BASE, SPLIT), f.files(f.originalDir))

        // The original is back on the phone (from Play): a new flow replaces the old record and its files.
        f.installed = ORIGINAL_INSTALLED
        val fresh = f.flow.begin(f.app())
        assertEquals(GenericIntent.PATCH, fresh.intent)
        assertEquals(emptyList<SavedApk>(), fresh.original)
        assertNull(fresh.patchedBundleId)
        assertEquals(fresh, f.record())
        assertEquals(emptyList<String>(), f.files(f.originalDir))
    }

    @Test
    fun stop_only_cleans_up_while_original_installed() {
        for (asked in listOf(false, true)) {
            // The original is installed, at every step before it is removed: Stop forgets everything.
            val f = fixture()
            f.built()
            if (asked) assertNull(f.flow.askedToUninstall(PKG))
            assertEquals(GenericPhase.RemoveOriginal(asked), f.flow.phase(PKG))
            assertTrue(f.flow.stop(PKG))
            assertNull(f.records.get(PKG))
            assertFalse(f.packageDir.exists())

            // The original is already removed: no Stop, and nothing is deleted.
            val g = fixture()
            g.built()
            if (asked) assertNull(g.flow.askedToUninstall(PKG))
            val before = g.record()
            g.installed = null
            assertEquals(GenericPhase.InstallPatched, g.flow.phase(PKG))
            assertFalse(g.flow.stop(PKG))
            assertEquals(before, g.record())
            assertEquals(listOf(BASE, SPLIT), g.files(g.originalDir))
            assertEquals(listOf(BASE, SPLIT), g.files(g.builtDir))

            // Re-read at the call: once the original is back, Stop cleans up.
            g.installed = ORIGINAL_INSTALLED
            assertTrue(g.flow.stop(PKG))
            assertFalse(g.packageDir.exists())
        }

        // Before anything was saved.
        val early = fixture()
        early.flow.begin(early.app())
        assertEquals(GenericPhase.SaveAndBuild, early.flow.phase(PKG))
        assertTrue(early.flow.stop(PKG))
        assertNull(early.records.get(PKG))

        // A Play update arrived mid-flow: the original signer is still installed.
        val updated = fixture()
        updated.built()
        updated.installed = ORIGINAL_INSTALLED.copy(versionCode = VERSION_CODE + 1, baseSha256 = "f".repeat(64))
        assertTrue(updated.flow.phase(PKG) is GenericPhase.Attention)
        assertTrue(updated.flow.stop(PKG))
        assertFalse(updated.packageDir.exists())

        // Our patched copy is installed: not the original, so no Stop.
        val ours = fixture()
        val built = ours.built()
        ours.installed = PATCHED_INSTALLED
        assertEquals(GenericPhase.Patched, ours.flow.phase(PKG))
        assertFalse(ours.flow.stop(PKG))
        assertEquals(built, ours.record())

        // Nothing to stop.
        val none = fixture()
        assertFalse(none.flow.stop(PKG))
        none.patched()
        assertFalse(none.flow.stop(PKG))
    }

    @Test
    fun install_patched_passes_built_files_base_first_with_no_extra_trust() = runTest {
        val f = fixture()
        f.built()
        // Not while the original is still installed.
        assertEquals(refused("Nothing was installed, because Example is at another step now."), f.flow.installPatched(PKG))
        assertEquals(emptyList<Pair<List<File>, Set<String>>>(), f.installs)

        assertNull(f.flow.askedToUninstall(PKG))
        f.installed = null
        assertEquals(InstallOutcome.Success, f.flow.installPatched(PKG))
        assertEquals(listOf(listOf(File(f.builtDir, BASE), File(f.builtDir, SPLIT)) to emptySet<String>()), f.installs)
    }

    @Test
    fun install_patched_refuses_a_damaged_patched_copy() = runTest {
        val f = fixture()
        f.built()
        f.installed = null
        val message = refused("The patched copy of Example is damaged, so it wasn't installed.")

        damage(File(f.builtDir, SPLIT))
        assertEquals(message, f.flow.installPatched(PKG))
        File(f.builtDir, SPLIT).delete()
        assertEquals(message, f.flow.installPatched(PKG))
        assertEquals(emptyList<Pair<List<File>, Set<String>>>(), f.installs)
        assertEquals(GenericPhase.InstallPatched, f.flow.phase(PKG))
    }

    @Test
    fun install_failure_offers_restore_and_restore_trusts_original_signers() = runTest {
        val f = fixture()
        f.built()
        assertNull(f.flow.askedToUninstall(PKG))
        f.installed = null
        val failure = InstallOutcome.Failure("The install was cancelled.", InstallFailure.ABORTED)
        f.installAnswer = failure
        val beforeInstall = f.record()

        assertEquals(failure, f.flow.installPatched(PKG))
        assertEquals(beforeInstall, f.record())
        assertEquals(GenericPhase.InstallPatched, f.flow.phase(PKG))

        // "Put the original back": nothing is installed, so straight to installing it.
        assertNull(f.flow.startRestore(PKG))
        assertEquals(GenericIntent.RESTORE, f.record().intent)
        assertEquals(GenericPhase.InstallOriginal, f.flow.phase(PKG))

        f.installAnswer = InstallOutcome.Success
        assertEquals(InstallOutcome.Success, f.flow.installOriginal(PKG))
        assertEquals(2, f.installs.size)
        assertEquals(listOf(File(f.originalDir, BASE), File(f.originalDir, SPLIT)) to setOf(DEV_CERT), f.installs.last())
    }

    @Test
    fun install_original_only_at_its_step_and_reverifies_saved_copies() = runTest {
        val f = fixture()
        f.patched()
        assertEquals(refused("Nothing was installed, because Example is at another step now."), f.flow.installOriginal(PKG))
        assertNull(f.flow.startRestore(PKG))
        // The patched copy is still installed.
        assertTrue(f.flow.phase(PKG) is GenericPhase.RemovePatched)
        assertEquals(refused("Nothing was installed, because Example is at another step now."), f.flow.installOriginal(PKG))

        f.installed = null
        damage(File(f.originalDir, BASE))
        assertEquals(refused("The saved original is damaged, so it can't be put back."), f.flow.installOriginal(PKG))
        assertEquals(emptyList<Pair<List<File>, Set<String>>>(), f.installs)
        assertEquals(GenericPhase.InstallOriginal, f.flow.phase(PKG))
    }

    @Test
    fun restore_refuses_damaged_saved_original() {
        for (harm in listOf<(File) -> Unit>(::damage, { it.delete() })) {
            val f = fixture()
            val patched = f.patched()
            harm(File(f.originalDir, SPLIT))
            assertEquals("The saved original is damaged, so it can't be put back.", f.flow.startRestore(PKG))
            assertEquals(patched, f.record())
            assertEquals(GenericPhase.Patched, f.flow.phase(PKG))
            assertEquals(emptyList<Pair<List<File>, Set<String>>>(), f.installs)
        }
    }

    @Test
    fun finish_patch_moves_built_facts_to_patched_and_deletes_built() {
        val f = fixture()
        f.built()
        f.clock = 2_000L
        assertNull(f.flow.askedToUninstall(PKG))
        val asked = f.record()

        // Not before our copy is installed.
        f.flow.finish(PKG)
        assertEquals(asked, f.record())
        f.installed = null
        f.flow.finish(PKG)
        assertEquals(asked, f.record())

        f.installed = PATCHED_INSTALLED
        f.clock = 9_000L
        f.flow.finish(PKG)
        assertEquals(
            asked.copy(
                built = emptyList(),
                builtBundleId = null,
                builtTokenExpiresAt = null,
                intent = null,
                uninstallAskedAt = null,
                patchedBundleId = BUNDLE_ID,
                tokenExpiresAt = EXPIRES_AT,
                patchedAt = 9_000L,
            ),
            f.record(),
        )
        assertFalse(f.builtDir.exists())
        assertEquals(listOf(BASE, SPLIT), f.files(f.originalDir))
        assertEquals(GenericPhase.Patched, f.flow.phase(PKG))

        // Done is done.
        val finished = f.record()
        f.clock = 10_000L
        f.flow.finish(PKG)
        assertEquals(finished, f.record())
    }

    @Test
    fun finish_restore_removes_record() {
        val f = fixture()
        f.patched()
        assertNull(f.flow.startRestore(PKG))
        val restoring = f.record()

        f.flow.finish(PKG)
        assertEquals(restoring, f.record())
        f.installed = null
        f.flow.finish(PKG)
        assertEquals(restoring, f.record())

        f.installed = ORIGINAL_INSTALLED
        assertEquals(GenericPhase.Restored, f.flow.phase(PKG))
        f.flow.finish(PKG)
        assertNull(f.records.get(PKG))
        assertFalse(f.packageDir.exists())
        assertNull(f.flow.phase(PKG))
    }

    @Test
    fun refresh_finish_updates_bundle_id_and_token_expiry() = runTest {
        val f = fixture()
        val patched = f.patched()
        assertNull(f.flow.startRefresh(PKG))
        assertEquals(patched.copy(intent = GenericIntent.REFRESH), f.record())
        assertEquals(GenericPhase.SaveAndBuild, f.flow.phase(PKG))

        f.refreshBuilt()
        assertEquals(GenericPhase.InstallUpdate, f.flow.phase(PKG))
        assertEquals(InstallOutcome.Success, f.flow.installPatched(PKG))
        assertEquals(listOf(listOf(File(f.builtDir, BASE), File(f.builtDir, SPLIT)) to emptySet<String>()), f.installs)

        f.installed = InstalledState(setOf(USER_CERT), VERSION_CODE, sha256(UPDATE_BASE))
        assertEquals(GenericPhase.Patched, f.flow.phase(PKG))
        f.clock = 7_000L
        f.flow.finish(PKG)
        assertEquals(patched.copy(patchedBundleId = NEW_BUNDLE_ID, tokenExpiresAt = NEW_EXPIRES_AT, patchedAt = 7_000L), f.record())
        assertFalse(f.builtDir.exists())
        assertEquals(listOf(BASE, SPLIT), f.files(f.originalDir))
    }

    @Test
    fun refresh_starts_only_from_a_finished_patch() {
        val f = fixture()
        val cant = "Example can't be refreshed right now."
        assertEquals("$PKG can't be refreshed right now.", f.flow.startRefresh(PKG))
        f.built()
        assertEquals(cant, f.flow.startRefresh(PKG)) // mid-patch
        f.patched()
        f.installed = ORIGINAL_INSTALLED
        assertEquals(cant, f.flow.startRefresh(PKG)) // not our copy
        assertNull(f.record().intent)
        f.installed = PATCHED_INSTALLED
        assertNull(f.flow.startRefresh(PKG))
        assertNull(f.flow.startRefresh(PKG)) // already refreshing
        assertEquals(GenericIntent.REFRESH, f.record().intent)
    }

    @Test
    fun stop_refresh_deletes_built_and_clears_intent() {
        val f = fixture()
        val patched = f.patched()
        assertNull(f.flow.startRefresh(PKG))
        f.refreshBuilt()
        assertEquals(GenericPhase.InstallUpdate, f.flow.phase(PKG))

        assertTrue(f.flow.stop(PKG))
        assertEquals(patched, f.record())
        assertFalse(f.builtDir.exists())
        assertEquals(listOf(BASE, SPLIT), f.files(f.originalDir))
    }

    @Test
    fun stop_restore_only_while_patched_copy_installed() {
        val f = fixture()
        val patched = f.patched()
        assertNull(f.flow.startRestore(PKG))
        assertNull(f.flow.askedToUninstall(PKG))
        assertEquals(GenericPhase.RemovePatched(true), f.flow.phase(PKG))

        // The patched copy is gone: putting the original back is the only way on.
        f.installed = null
        assertFalse(f.flow.stop(PKG))
        assertEquals(GenericIntent.RESTORE, f.record().intent)

        f.installed = PATCHED_INSTALLED
        assertTrue(f.flow.stop(PKG))
        assertEquals(patched, f.record())
        assertEquals(listOf(BASE, SPLIT), f.files(f.originalDir))
    }

    @Test
    fun delete_saved_original_only_when_idle_and_patched() {
        val f = fixture()
        f.built()
        assertFalse(f.flow.deleteSavedOriginal(PKG)) // the original is still installed
        f.installed = PATCHED_INSTALLED
        assertEquals(GenericPhase.Patched, f.flow.phase(PKG))
        assertFalse(f.flow.deleteSavedOriginal(PKG)) // not finished
        assertEquals(listOf(BASE, SPLIT), f.files(f.originalDir))

        val patched = f.patched()
        val bytes = (BASE_BYTES.size + SPLIT_BYTES.size).toLong()
        assertEquals(bytes, f.flow.savedOriginalBytes(PKG))
        f.busy = true
        assertFalse(f.flow.deleteSavedOriginal(PKG))
        f.busy = false
        f.installed = null
        assertEquals(GenericPhase.Removed, f.flow.phase(PKG))
        assertFalse(f.flow.deleteSavedOriginal(PKG))
        assertEquals(patched, f.record())
        assertEquals(listOf(BASE, SPLIT), f.files(f.originalDir))

        f.installed = PATCHED_INSTALLED
        assertTrue(f.flow.deleteSavedOriginal(PKG))
        assertEquals(patched.copy(original = emptyList()), f.record())
        assertFalse(f.originalDir.exists())
        assertEquals(0L, f.flow.savedOriginalBytes(PKG))
        assertEquals(GenericPhase.Patched, f.flow.phase(PKG))
        assertFalse(f.flow.deleteSavedOriginal(PKG))
    }

    @Test
    fun asked_to_uninstall_is_persisted_before_returning() {
        val f = fixture()
        f.built()
        f.clock = 4_242L
        assertNull(f.flow.askedToUninstall(PKG))
        assertEquals(4_242L, GenericRecords(f.recordsRoot).get(PKG)!!.uninstallAskedAt)
        assertEquals(GenericPhase.RemoveOriginal(true), f.flow.phase(PKG))
    }

    @Test
    fun asked_to_uninstall_only_at_a_remove_step_with_intact_copies() {
        // Before the original is saved and the patched copy built.
        val f = fixture()
        f.flow.begin(f.app())
        assertEquals("Example isn't ready to be removed.", f.flow.askedToUninstall(PKG))
        assertNull(f.record().uninstallAskedAt)

        // Saved and built, but a copy was damaged since: the original stays.
        for (dir in listOf(Fixture::originalDir, Fixture::builtDir)) {
            val g = fixture()
            g.built()
            damage(File(dir(g), SPLIT))
            assertEquals("The saved copies of Example are damaged, so it wasn't removed. Stop, then patch it again.", g.flow.askedToUninstall(PKG))
            assertNull(g.record().uninstallAskedAt)
        }

        // Already removed.
        val gone = fixture()
        gone.built()
        gone.installed = null
        assertEquals("Example isn't ready to be removed.", gone.flow.askedToUninstall(PKG))

        // Putting the original back: the patched copy stays unless the saved original is intact.
        val restoring = fixture()
        restoring.patched()
        assertNull(restoring.flow.startRestore(PKG))
        damage(File(restoring.originalDir, BASE))
        assertEquals("The saved original is damaged, so it can't be put back.", restoring.flow.askedToUninstall(PKG))
        assertNull(restoring.record().uninstallAskedAt)

        // No record.
        assertNotNull(fixture().flow.askedToUninstall(PKG))
    }

    @Test
    fun stop_allowed_when_nothing_was_saved() {
        // Removed before its original was saved: nothing on disk is worth keeping.
        val f = fixture()
        f.flow.begin(f.app())
        f.installed = null
        assertEquals(GenericPhase.Attention("Example was removed before its original was saved."), f.flow.phase(PKG))
        assertTrue(f.flow.stop(PKG))
        assertNull(f.records.get(PKG))
        assertFalse(f.packageDir.exists())

        // Whatever is installed meanwhile.
        val g = fixture()
        g.flow.begin(g.app())
        g.installed = PATCHED_INSTALLED
        assertTrue(g.flow.stop(PKG))
        assertFalse(g.packageDir.exists())

        // A saved original alone keeps Stop away once the app is gone: it is the only copy.
        val h = fixture()
        h.flow.begin(h.app())
        val saved = h.record().copy(original = save(h.originalDir, BASE to BASE_BYTES, SPLIT to SPLIT_BYTES))
        h.records.put(saved)
        h.installed = null
        assertEquals(GenericPhase.SaveAndBuild, h.flow.phase(PKG))
        assertFalse(h.flow.stop(PKG))
        assertEquals(saved, h.record())
        assertEquals(listOf(BASE, SPLIT), h.files(h.originalDir))
    }

    @Test
    fun stop_restore_records_an_installed_build_and_drops_the_rest() = runTest {
        // A patch installed but never finished, then put back: the installed build is the patch.
        val f = fixture()
        val built = f.built()
        f.installed = null
        assertEquals(InstallOutcome.Success, f.flow.installPatched(PKG))
        f.installed = PATCHED_INSTALLED
        assertNull(f.flow.startRestore(PKG))
        assertTrue(f.flow.phase(PKG) is GenericPhase.RemovePatched)
        f.clock = 6_000L
        assertTrue(f.flow.stop(PKG))
        assertEquals(
            built.copy(
                built = emptyList(),
                builtBundleId = null,
                builtTokenExpiresAt = null,
                intent = null,
                patchedBundleId = BUNDLE_ID,
                tokenExpiresAt = EXPIRES_AT,
                patchedAt = 6_000L,
            ),
            f.record(),
        )
        assertFalse(f.builtDir.exists())
        assertEquals(listOf(BASE, SPLIT), f.files(f.originalDir))

        // A Refresh's build that never went in: the patch stays as it was, and the build goes.
        val g = fixture()
        val patched = g.patched()
        assertNull(g.flow.startRefresh(PKG))
        g.refreshBuilt()
        assertNull(g.flow.startRestore(PKG))
        assertTrue(g.flow.phase(PKG) is GenericPhase.RemovePatched)
        assertTrue(g.flow.stop(PKG))
        assertEquals(patched, g.record())
        assertFalse(g.builtDir.exists())
    }

    @Test
    fun asked_to_uninstall_needs_the_signing_key() {
        val f = fixture()
        f.built()
        f.cert = null
        assertEquals(GenericPhase.RemoveOriginal(false), f.flow.phase(PKG))
        assertEquals("Sign in again before removing Example.", f.flow.askedToUninstall(PKG))
        assertNull(f.record().uninstallAskedAt)

        f.cert = USER_CERT
        assertNull(f.flow.askedToUninstall(PKG))
    }

    @Test
    fun adopt_records_bundle_id_and_no_original() {
        val f = fixture()
        f.installed = PATCHED_INSTALLED
        val ours = f.app(setOf(USER_CERT))
        val record = f.flow.adopt(ours, INFO)
        assertEquals(
            GenericRecord(
                packageName = PKG,
                label = LABEL,
                versionName = VERSION_NAME,
                versionCode = VERSION_CODE,
                originalSigners = emptySet(),
                apkNames = listOf(BASE, SPLIT),
                patchedBundleId = BUNDLE_ID,
            ),
            record,
        )
        assertEquals(record, f.record())
        assertNull(record.tokenExpiresAt)
        assertEquals(GenericPhase.Patched, f.flow.phase(PKG))
        assertThrows(IllegalStateException::class.java) { f.flow.adopt(ours, INFO) }

        assertEquals("There's no saved original of Example to put back.", f.flow.startRestore(PKG))
        assertEquals(record, f.record())
        assertEquals(0L, f.flow.savedOriginalBytes(PKG))
        assertFalse(f.flow.deleteSavedOriginal(PKG))

        assertNull(f.flow.startRefresh(PKG))
        assertEquals(GenericIntent.REFRESH, f.record().intent)
        assertEquals(GenericPhase.SaveAndBuild, f.flow.phase(PKG))
    }

    @Test
    fun a_running_job_or_install_blocks_every_change() = runTest {
        val busy = "Wait for the current step to finish, then try again."
        val f = fixture()
        val built = f.built()
        f.busy = true
        assertFalse(f.flow.stop(PKG))
        assertEquals(busy, f.flow.askedToUninstall(PKG))
        f.installed = PATCHED_INSTALLED
        f.flow.finish(PKG)
        assertEquals(built, f.record())
        f.installed = null
        assertEquals(refused(busy), f.flow.installPatched(PKG))
        assertEquals(emptyList<Pair<List<File>, Set<String>>>(), f.installs)

        f.busy = false
        assertNull(f.flow.startRestore(PKG))
        f.busy = true
        assertEquals(refused(busy), f.flow.installOriginal(PKG))
        assertEquals(emptyList<Pair<List<File>, Set<String>>>(), f.installs)
        assertEquals(GenericPhase.InstallOriginal, f.flow.phase(PKG))

        val patched = f.patched()
        assertEquals(busy, f.flow.startRestore(PKG))
        assertEquals("Example is busy. Wait for it to finish.", f.flow.startRefresh(PKG))
        assertFalse(f.flow.deleteSavedOriginal(PKG))
        assertEquals(patched, f.record())
        assertEquals(listOf(BASE, SPLIT), f.files(f.originalDir))

        // The flow's own install in progress holds off changes too.
        f.busy = false
        assertNull(f.flow.startRefresh(PKG))
        f.refreshBuilt()
        val installing = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        f.duringInstall = {
            installing.complete(Unit)
            answer.await()
        }
        val install = async { f.flow.installPatched(PKG) }
        installing.await()
        val refreshing = f.record()
        assertFalse(f.flow.stop(PKG))
        assertEquals(busy, f.flow.startRestore(PKG))
        assertEquals(refused(busy), f.flow.installPatched(PKG))
        assertEquals(refreshing, f.record())
        assertEquals(1, f.installs.size)

        answer.complete(Unit)
        assertEquals(InstallOutcome.Success, install.await())
        assertTrue(f.flow.stop(PKG))
    }

    private companion object {
        const val PKG = "com.example.app"
        const val LABEL = "Example"
        const val VERSION_NAME = "1.0"
        const val VERSION_CODE = 7L
        const val BASE = "base.apk"
        const val SPLIT = "split_config.arm64_v8a.apk"
        const val EXPIRES_AT = 1_800_000_000_000L
        const val OLD_EXPIRES_AT = 1_700_000_000_000L
        const val NEW_EXPIRES_AT = 1_830_000_000_000L
        val USER_CERT = "a".repeat(64)
        val DEV_CERT = "d".repeat(64)
        val BUNDLE_ID = "b".repeat(64)
        val OLD_BUNDLE_ID = "0".repeat(64)
        val NEW_BUNDLE_ID = "c".repeat(64)
        val BASE_BYTES = "the installed base apk".toByteArray()
        val SPLIT_BYTES = "the installed arm64 split".toByteArray()
        val BUILT_BASE = "the patched base apk".toByteArray()
        val BUILT_SPLIT = "the re-signed arm64 split".toByteArray()
        val UPDATE_BASE = "the refreshed base apk".toByteArray()
        val UPDATE_SPLIT = "the re-signed split again".toByteArray()
        val INFO = GenericPatchInfo(
            GenericPatchInfo.KIND, "1.0.0", "b".repeat(64), 1, "arm64-v8a", "lib/arm64-v8a", "AppLovin",
            listOf(GenericPatchInfo.ENTRY),
        )

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        val ORIGINAL_INSTALLED = InstalledState(setOf(DEV_CERT), VERSION_CODE, sha256(BASE_BYTES))
        val PATCHED_INSTALLED = InstalledState(setOf(USER_CERT), VERSION_CODE, sha256(BUILT_BASE))
    }
}
