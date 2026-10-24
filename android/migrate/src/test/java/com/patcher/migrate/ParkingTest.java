package com.patcher.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Parking takes its two directories as arguments precisely so this runs on a
 * JVM against temp dirs: the one operation that must never be half-done is a
 * rename, and a rename is testable anywhere.
 */
public class ParkingTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SIG =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private File wa;
    private File parkRoot;
    private Parking parking;

    @Before
    public void setUp() throws Exception {
        wa = new File(tmp.getRoot(), "media/com.whatsapp/WhatsApp");
        parkRoot = new File(tmp.getRoot(), "WAMigrate");
        write(new File(wa, "Databases/msgstore.db.crypt14"), new byte[]{'a', 'b', 'c'});
        write(new File(wa, "Media/WhatsApp Images/IMG-1.jpg"), new byte[500]);
        write(new File(wa, ".Shared/s.bin"), new byte[10]);
        parking = new Parking(wa, parkRoot);
    }

    private static void write(File f, byte[] bytes) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
    }

    @Test
    public void park_moves_the_whole_tree_and_leaves_the_source_gone() {
        Result r = parking.park("2.26.38.74", 263807433L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertTrue(r.summary, r.ok);
        assertFalse(wa.exists());
        assertTrue(new File(parkRoot, "WhatsApp/Databases/msgstore.db.crypt14").isFile());
        assertTrue(new File(parkRoot, "WhatsApp/Media/WhatsApp Images/IMG-1.jpg").isFile());
        assertTrue(new File(parkRoot, "WhatsApp/.Shared/s.bin").isFile());
    }

    @Test
    public void park_records_the_facts_the_uninstall_would_destroy() {
        parking.park("2.26.38.74", 263807433L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        ParkManifest m = parking.readManifest();
        assertNotNull(m);
        assertEquals(ParkManifest.State.PARKED, m.state);
        assertEquals("2.26.38.74", m.waVersionName);
        assertEquals(263807433L, m.waVersionCode);
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", m.waSignatureSha256);
        assertEquals("crypt14", m.crypt);
        assertEquals(3, m.treeFiles);
        assertEquals(513L, m.treeBytes);
        assertEquals(1, m.databases.size());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                m.databases.get(0).sha256);
    }

    @Test
    public void park_refuses_when_there_is_nothing_to_park() throws Exception {
        File empty = tmp.newFolder("empty");
        Result r = new Parking(empty, parkRoot).park("v", 1L, SIG);
        assertFalse(r.ok);
        assertTrue(r.summary.toLowerCase().contains("nothing to park"));
        // no manifest should have been written
        assertFalse(new File(parkRoot, ParkManifest.FILE_NAME).exists());
    }

    @Test
    public void park_refuses_when_a_copy_is_already_parked() {
        assertTrue(parking.park("v", 1L, SIG).ok);
        write_again();
        Result second = parking.park("v", 1L, SIG);
        assertFalse(second.ok);
        assertTrue(second.summary.toLowerCase().contains("already"));
        // and the first copy is untouched
        assertTrue(new File(parkRoot, "WhatsApp/Databases/msgstore.db.crypt14").isFile());
        // and the existing manifest still reads PARKED
        assertEquals(ParkManifest.State.PARKED, parking.readManifest().state);
    }

    private void write_again() {
        try {
            write(new File(wa, "Databases/msgstore.db.crypt14"), new byte[]{'z'});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void park_proceeds_when_the_park_root_exists_but_holds_nothing() {
        assertTrue(parkRoot.mkdirs());
        assertTrue(parking.park("v", 1L, SIG).ok);
        assertTrue(new File(parkRoot, "WhatsApp/Databases/msgstore.db.crypt14").isFile());
    }

    @Test
    public void a_crypt15_backup_is_reported_as_crypt15() throws Exception {
        File wa15 = new File(tmp.getRoot(), "wa15");
        write(new File(wa15, "Databases/msgstore.db.crypt15"), new byte[]{'q'});
        Parking p = new Parking(wa15, new File(tmp.getRoot(), "park15"));
        assertTrue(p.park("v", 1L, SIG).ok);
        assertEquals("crypt15", p.readManifest().crypt);
    }

    @Test
    public void park_that_cannot_rename_changes_nothing_and_leaves_no_manifest() throws Exception {
        File waParent = wa.getParentFile();          // media/com.whatsapp
        assertTrue(waParent.setWritable(false));
        try {
            Result r = parking.park("v", 1L, SIG);
            assertFalse(r.summary, r.ok);
            // the tree is untouched where it started
            assertTrue(new File(wa, "Databases/msgstore.db.crypt14").isFile());
            assertTrue(new File(wa, "Media/WhatsApp Images/IMG-1.jpg").isFile());
            // and the manifest this call wrote has been taken back out
            assertFalse(new File(parkRoot, ParkManifest.FILE_NAME).exists());
            // and the failure reason is the rename, not an earlier step
            assertTrue(r.summary.toLowerCase().contains("refused by the filesystem"));
        } finally {
            waParent.setWritable(true);
        }
    }

    @Test
    public void park_sets_aside_an_earlier_moves_restored_manifest_and_keeps_it_on_failure() throws Exception {
        // A completed earlier move: its RESTORED manifest and the databases kept beside it.
        assertTrue(parkRoot.mkdirs());
        ParkManifest restored = new ParkManifest(ParkManifest.State.RESTORED, System.currentTimeMillis(),
                "1.0", 1L, SIG, "crypt14", 0, 0L, new ArrayList<>());
        Parking.atomicWriteText(new File(parkRoot, ParkManifest.FILE_NAME), restored.render());
        write(new File(parkRoot, "Databases-kept/msgstore.db.crypt14"), new byte[]{9});

        File waParent = wa.getParentFile();
        assertTrue(waParent.setWritable(false));
        try {
            Result r = parking.park("v", 1L, SIG);
            assertFalse(r.ok);
        } finally {
            waParent.setWritable(true);
        }

        // Set aside whole, never overwritten, and still there after the failure.
        List<File> previous = Leftovers.previousCopies(parkRoot);
        assertEquals(1, previous.size());
        ParkManifest preserved = ParkManifest.parse(
                Parking.readText(new File(previous.get(0), ParkManifest.FILE_NAME)));
        assertNotNull(preserved);
        assertEquals(ParkManifest.State.RESTORED, preserved.state);
        assertTrue(new File(previous.get(0), "Databases-kept/msgstore.db.crypt14").isFile());
        // This park's own PARKING manifest is not left behind.
        assertNull(parking.readManifest());
    }

    @Test
    public void park_refuses_when_parked_folder_exists_but_manifest_is_missing() throws Exception {
        // Create parkRoot/WhatsApp with a file in it, but no manifest.
        // This exercises the hasChildren(parkedFolder()) refusal and tests the case where
        // the manifest-state check is skipped (since there is no manifest).
        File parkedFolder = new File(parkRoot, "WhatsApp");
        write(new File(parkedFolder, "Databases/test.db"), new byte[]{'x'});

        Result r = parking.park("v", 1L, SIG);
        assertFalse(r.ok);
        assertTrue(r.summary.toLowerCase().contains("already parked"));
    }

    @Test
    public void park_fails_when_a_database_is_unreadable() throws Exception {
        File backup = new File(wa, "Databases/msgstore.db.crypt14");
        assertTrue(backup.setReadable(false));
        try {
            Result r = parking.park("v", 1L, SIG);
            assertFalse(r.ok);
            // nothing moved
            assertTrue(wa.exists());
            assertTrue(new File(wa, "Databases/msgstore.db.crypt14").isFile());
            // no manifest written
            assertFalse(new File(parkRoot, ParkManifest.FILE_NAME).exists());
        } finally {
            backup.setReadable(true);
        }
    }

    @Test
    public void park_succeeds_when_the_destination_directory_is_empty() throws Exception {
        File dest = new File(parkRoot, "WhatsApp");
        assertTrue(dest.mkdirs());
        Result r = parking.park("v", 1L, SIG);
        assertTrue(r.summary, r.ok);
        assertTrue(new File(dest, "Databases/msgstore.db.crypt14").isFile());
    }

    @Test
    public void park_refuses_when_a_parking_manifest_already_exists() throws Exception {
        Result first = parking.park("v", 1L, SIG);
        assertTrue(first.ok);
        // Manually change the manifest state back to PARKING to simulate an interrupted park.
        ParkManifest m = parking.readManifest();
        ParkManifest parking_state = new ParkManifest(ParkManifest.State.PARKING, m.parkedAtMs,
                m.waVersionName, m.waVersionCode, m.waSignatureSha256, m.crypt,
                m.treeFiles, m.treeBytes, m.databases);
        Parking.atomicWriteText(new File(parkRoot, ParkManifest.FILE_NAME), parking_state.render());

        // Recreate the wa folder with some content (it was moved by the first park call)
        write(new File(wa, "Databases/msgstore.db.crypt14"), new byte[]{'z'});
        Result second = parking.park("v", 1L, SIG);
        assertFalse(second.ok);
        assertTrue(second.summary.toLowerCase().contains("parking"));
    }

    @Test
    public void put_back_returns_the_tree_and_verifies_the_databases() {
        assertTrue(parking.park("v", 1L, SIG).ok);
        Result r = parking.putBack();
        assertTrue(r.summary, r.ok);
        assertTrue(new File(wa, "Databases/msgstore.db.crypt14").isFile());
        assertTrue(new File(wa, "Media/WhatsApp Images/IMG-1.jpg").isFile());
        assertTrue(new File(wa, ".Shared/s.bin").isFile());
        assertFalse(new File(parkRoot, "WhatsApp").exists());
        assertEquals(ParkManifest.State.RESTORED, parking.readManifest().state);
    }

    @Test
    public void put_back_creates_the_destination_parent_when_the_uninstall_removed_it() {
        assertTrue(parking.park("v", 1L, SIG).ok);
        // Uninstalling WhatsApp deletes all of Android/media/com.whatsapp, so the
        // parent is gone by the time put-back runs. This is the ENOENT that broke
        // the restore on 2026-09-30.
        assertTrue(deleteTree(new File(tmp.getRoot(), "media")));
        assertFalse(wa.getParentFile().exists());
        assertTrue(parking.putBack().ok);
        assertTrue(new File(wa, "Databases/msgstore.db.crypt14").isFile());
    }

    @Test
    public void put_back_keeps_a_copy_of_the_databases_so_a_data_clear_cannot_finish_the_job() {
        assertTrue(parking.park("v", 1L, SIG).ok);
        assertTrue(parking.putBack().ok);
        assertTrue(new File(parkRoot, "Databases-kept/msgstore.db.crypt14").isFile());
    }

    @Test
    public void put_back_refuses_rather_than_merge_into_a_non_empty_folder() throws Exception {
        // Review Focus 2: the patched app was launched first and made its own
        // folder. Merging leaves two backups in Databases/ and WhatsApp restores
        // whichever it likes.
        assertTrue(parking.park("v", 1L, SIG).ok);
        write(new File(wa, "Databases/msgstore.db.crypt14"), new byte[]{'n', 'e', 'w'});
        Result r = parking.putBack();
        assertFalse(r.ok);
        assertTrue(r.summary.toLowerCase().contains("in the way"));
        assertTrue(r.details.toString().contains("Databases"));
        // nothing moved
        assertTrue(new File(parkRoot, "WhatsApp/Databases/msgstore.db.crypt14").isFile());
    }

    @Test
    public void put_back_deletes_an_empty_destination_and_proceeds() {
        assertTrue(parking.park("v", 1L, SIG).ok);
        assertTrue(wa.mkdirs());
        assertTrue(parking.putBack().ok);
        assertTrue(new File(wa, "Databases/msgstore.db.crypt14").isFile());
    }

    @Test
    public void put_back_reports_a_hash_mismatch_instead_of_claiming_success() throws Exception {
        assertTrue(parking.park("v", 1L, SIG).ok);
        // Something edited the parked backup while it sat there.
        write(new File(parkRoot, "WhatsApp/Databases/msgstore.db.crypt14"), new byte[]{'x', 'y', 'z'});
        Result r = parking.putBack();
        assertFalse(r.ok);
        assertTrue(r.details.toString().contains("SHA-256"));
        // The manifest must not be flipped to RESTORED; it stays PARKED so the user can retry.
        ParkManifest m = parking.readManifest();
        assertEquals(ParkManifest.State.PARKED, m.state);
        // Nothing moved because we failed verification before the rename.
        assertTrue(new File(parkRoot, "WhatsApp/Databases/msgstore.db.crypt14").isFile());
        assertFalse(new File(wa, "Databases").exists());
    }

    @Test
    public void put_back_notes_a_changed_tree_without_calling_it_a_failure() throws Exception {
        // The spec's rule: report, do not block. The databases are what the
        // hashes already proved; an extra photograph is information, not a fault.
        assertTrue(parking.park("v", 1L, SIG).ok);
        write(new File(parkRoot, "WhatsApp/Media/extra.jpg"), new byte[7]);
        Result r = parking.putBack();
        assertTrue(r.summary, r.ok);
        assertTrue(r.details.toString().contains("wrote here in between"));
    }

    @Test
    public void put_back_accepts_a_parking_manifest_when_the_folder_is_present() throws Exception {
        // A crash between park()'s rename and its flip leaves the data parked
        // under a PARKING manifest. Refusing it would strand the user with their
        // data parked and no supported way back.
        assertTrue(parking.park("v", 1L, SIG).ok);
        ParkManifest m = parking.readManifest();
        ParkManifest parking_state = new ParkManifest(ParkManifest.State.PARKING, m.parkedAtMs,
                m.waVersionName, m.waVersionCode, m.waSignatureSha256, m.crypt,
                m.treeFiles, m.treeBytes, m.databases);
        Parking.atomicWriteText(new File(parkRoot, ParkManifest.FILE_NAME), parking_state.render());

        Result r = parking.putBack();
        assertTrue(r.summary, r.ok);
        assertTrue(new File(wa, "Databases/msgstore.db.crypt14").isFile());
        // The manifest must end up RESTORED.
        assertEquals(ParkManifest.State.RESTORED, parking.readManifest().state);
    }

    @Test
    public void put_back_refuses_when_nothing_is_parked() {
        Result r = parking.putBack();
        assertFalse(r.ok);
        assertTrue(r.summary.toLowerCase().contains("nothing"));
    }

    @Test
    public void put_back_that_cannot_write_the_manifest_has_already_moved_the_data() throws Exception {
        assertTrue(parking.park("v", 1L, SIG).ok);
        // atomicWriteText writes "<manifest>.tmp" then renames it over the manifest.
        // A directory in that spot fails the write while leaving the manifest readable.
        assertTrue(new File(parkRoot, ParkManifest.FILE_NAME + ".tmp").mkdirs());

        Result r = parking.putBack();
        assertFalse(r.summary, r.ok);
        // The point of the test: the data is already back, because the rename happens
        // BEFORE the manifest write. If those were ever swapped, the write would fail
        // first and the tree would still be parked -- and this assertion would fail.
        assertTrue(new File(wa, "Databases/msgstore.db.crypt14").isFile());
        assertTrue(new File(wa, "Media/WhatsApp Images/IMG-1.jpg").isFile());
        assertFalse(new File(parkRoot, "WhatsApp").exists());
        // Check the failure really is the manifest write.
        assertTrue(r.summary.toLowerCase().contains("restored"));
    }

    private static boolean deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        return f.delete();
    }

    @Test
    public void park_succeeds_over_a_stale_parking_manifest_with_nothing_parked() throws Exception {
        // A PARKING manifest left by a park whose rename never ran: park, then undo the rename.
        assertTrue(parking.park("2.26.38.74", 263807433L, SIG).ok);
        File parked = new File(parkRoot, "WhatsApp");
        assertTrue(parked.renameTo(wa));
        ParkManifest m = parking.readManifest();
        Parking.atomicWriteText(new File(parkRoot, ParkManifest.FILE_NAME),
                m.withState(ParkManifest.State.PARKING).render());
        Result r = parking.park("2.26.38.74", 263807433L, SIG);
        assertTrue(r.summary, r.ok);
        assertEquals(ParkManifest.State.PARKED, parking.readManifest().state);
    }

    @Test
    public void put_back_from_a_parking_manifest_leaves_it_flipped_to_parked() throws Exception {
        assertTrue(parking.park("2.26.38.74", 263807433L, SIG).ok);
        ParkManifest m = parking.readManifest();
        Parking.atomicWriteText(new File(parkRoot, ParkManifest.FILE_NAME),
                m.withState(ParkManifest.State.PARKING).render());
        // Put-back verifies and then writes RESTORED; the PARKING flip must have happened
        // before the rename, which a successful run cannot show, so check the end state.
        Result r = parking.putBack();
        assertTrue(r.summary, r.ok);
        assertEquals(ParkManifest.State.RESTORED, parking.readManifest().state);
    }

    @Test
    public void park_sets_aside_an_unreadable_manifest_with_nothing_parked() throws Exception {
        assertTrue(parkRoot.mkdirs());
        Parking.writeText(new File(parkRoot, ParkManifest.FILE_NAME), "garbage");
        Result r = parking.park("v", 1L, SIG);
        assertTrue(r.summary, r.ok);
        assertEquals(ParkManifest.State.PARKED, parking.readManifest().state);
        List<File> previous = Leftovers.previousCopies(parkRoot);
        assertEquals(1, previous.size());
        assertEquals("garbage", Parking.readText(new File(previous.get(0), ParkManifest.FILE_NAME)));
    }

    @Test
    public void put_back_reuses_a_kept_copy_that_matches() throws Exception {
        assertTrue(parking.park("v", 1L, SIG).ok);
        // A put-back that stopped after making its kept copy: the same databases are already there.
        File kept = new File(parkRoot, "Databases-kept/msgstore.db.crypt14");
        write(kept, new byte[]{'a', 'b', 'c'});
        assertTrue(parking.putBack().ok);
        assertTrue(Leftovers.previousCopies(parkRoot).isEmpty());
        assertTrue(kept.isFile());
    }

    @Test
    public void put_back_sets_aside_a_kept_copy_that_does_not_match() throws Exception {
        assertTrue(parking.park("v", 1L, SIG).ok);
        write(new File(parkRoot, "Databases-kept/msgstore.db.crypt14"), new byte[]{'x'});
        assertTrue(parking.putBack().ok);
        List<File> previous = Leftovers.previousCopies(parkRoot);
        assertEquals(1, previous.size());
        assertTrue(new File(previous.get(0), "Databases-kept/msgstore.db.crypt14").isFile());
        assertEquals(3L, new File(parkRoot, "Databases-kept/msgstore.db.crypt14").length());
    }

    @Test
    public void the_kept_copy_keeps_the_backups_time() {
        assertTrue(new File(wa, "Databases/msgstore.db.crypt14").setLastModified(1_700_000_000_000L));
        assertTrue(parking.park("v", 1L, SIG).ok);
        assertTrue(parking.putBack().ok);
        assertEquals(1_700_000_000_000L,
                new File(parkRoot, "Databases-kept/msgstore.db.crypt14").lastModified());
    }

    // --- unpark: cancel with the original still installed (2026-10-24) ---

    private static final long NOW = 1_800_000_000_000L;

    private static String text(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), "UTF-8");
    }

    @Test
    public void unpark_into_an_empty_folder_puts_the_whole_tree_back_and_forgets_the_park() throws Exception {
        assertTrue(parking.park("v", 1L, SIG).ok);
        Result r = parking.unpark(NOW);
        assertTrue(r.summary, r.ok);
        assertEquals("abc", text(new File(wa, "Databases/msgstore.db.crypt14")));
        assertEquals(500L, new File(wa, "Media/WhatsApp Images/IMG-1.jpg").length());
        assertTrue(new File(wa, ".Shared/s.bin").isFile());
        assertFalse(new File(parkRoot, "WhatsApp").exists());
        assertFalse(new File(parkRoot, ParkManifest.FILE_NAME).exists());
        assertTrue(Leftovers.previousCopies(parkRoot).isEmpty());
    }

    @Test
    public void unpark_after_whatsapp_made_a_new_folder_merges_and_never_overwrites() throws Exception {
        assertTrue(parking.park("v", 1L, SIG).ok);
        // WhatsApp kept running: a newer backup, and a photo that arrived after the park.
        write(new File(wa, "Databases/msgstore.db.crypt14"), "newer".getBytes("UTF-8"));
        write(new File(wa, "Media/WhatsApp Images/IMG-2.jpg"), new byte[7]);

        Result r = parking.unpark(NOW);

        assertTrue(r.summary, r.ok);
        assertEquals("newer", text(new File(wa, "Databases/msgstore.db.crypt14")));
        assertEquals(500L, new File(wa, "Media/WhatsApp Images/IMG-1.jpg").length());
        assertEquals(7L, new File(wa, "Media/WhatsApp Images/IMG-2.jpg").length());
        assertTrue(new File(wa, ".Shared/s.bin").isFile());
        // The parked file whose place was taken is set aside with the manifest, not deleted.
        assertFalse(new File(parkRoot, "WhatsApp").exists());
        assertFalse(new File(parkRoot, ParkManifest.FILE_NAME).exists());
        List<File> previous = Leftovers.previousCopies(parkRoot);
        assertEquals(1, previous.size());
        assertEquals("abc", text(new File(previous.get(0), "WhatsApp/Databases/msgstore.db.crypt14")));
        assertTrue(new File(previous.get(0), ParkManifest.FILE_NAME).isFile());
        assertFalse(new File(previous.get(0), "WhatsApp/Media/WhatsApp Images/IMG-1.jpg").exists());
    }

    @Test
    public void unpark_carries_on_after_an_interruption() throws Exception {
        assertTrue(parking.park("v", 1L, SIG).ok);
        // Stopped after moving one file back.
        File shared = new File(wa, ".Shared/s.bin");
        shared.getParentFile().mkdirs();
        assertTrue(new File(parkRoot, "WhatsApp/.Shared/s.bin").renameTo(shared));

        Result r = parking.unpark(NOW);

        assertTrue(r.summary, r.ok);
        assertTrue(shared.isFile());
        assertEquals("abc", text(new File(wa, "Databases/msgstore.db.crypt14")));
        assertFalse(new File(parkRoot, "WhatsApp").exists());
        assertTrue(Leftovers.previousCopies(parkRoot).isEmpty());
    }

    @Test
    public void unpark_brings_back_a_park_that_was_interrupted() throws Exception {
        assertTrue(parking.park("v", 1L, SIG).ok);
        File manifest = new File(parkRoot, ParkManifest.FILE_NAME);
        Parking.atomicWriteText(manifest, parking.readManifest().withState(ParkManifest.State.PARKING).render());
        assertTrue(parking.unpark(NOW).ok);
        assertEquals("abc", text(new File(wa, "Databases/msgstore.db.crypt14")));
    }

    @Test
    public void unpark_with_nothing_parked_is_refused() {
        Result r = parking.unpark(NOW);
        assertFalse(r.ok);
        assertTrue(new File(wa, "Databases/msgstore.db.crypt14").isFile());
    }

    @Test
    public void unpark_after_a_put_back_is_refused() {
        assertTrue(parking.park("v", 1L, SIG).ok);
        assertTrue(parking.putBack().ok);
        assertFalse(parking.unpark(NOW).ok);
    }

    @Test
    public void unpark_finishes_when_only_the_manifest_was_left() throws Exception {
        assertTrue(parking.park("v", 1L, SIG).ok);
        // Stopped between the rename back and removing the manifest.
        assertTrue(new File(parkRoot, "WhatsApp").renameTo(wa));
        Result r = parking.unpark(NOW);
        assertTrue(r.summary, r.ok);
        assertFalse(new File(parkRoot, ParkManifest.FILE_NAME).exists());
        assertEquals("abc", text(new File(wa, "Databases/msgstore.db.crypt14")));
    }

    @Test
    public void unpark_with_only_a_manifest_and_an_empty_whatsapp_folder_is_refused() throws Exception {
        assertTrue(parking.park("v", 1L, SIG).ok);
        assertTrue(Parking.deleteTree(new File(parkRoot, "WhatsApp")));
        assertFalse(parking.unpark(NOW).ok);
        assertTrue(new File(parkRoot, ParkManifest.FILE_NAME).isFile());
    }
}
