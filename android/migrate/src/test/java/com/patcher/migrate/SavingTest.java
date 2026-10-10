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

/**
 * The copy that protects a move before step 4. Each refusal to replace it is its
 * own case: replacing it with the wrong backup is how a reinstalled, empty
 * WhatsApp would overwrite the real chats.
 */
public class SavingTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SIG = "a".repeat(64);
    private static final String OTHER_SIG = "b".repeat(64);
    private static final long INSTALLED = 1_700_000_000_000L;
    private static final long NOW = 1_800_000_000_000L;

    private File wa;
    private File parkRoot;
    private long free = Long.MAX_VALUE;
    private Saving saving;
    private int fill;

    @Before
    public void setUp() throws Exception {
        wa = new File(tmp.getRoot(), "media/com.whatsapp/WhatsApp");
        parkRoot = new File(tmp.getRoot(), "WAMigrate");
        backup("msgstore.db.crypt14", 1000, NOW - 60_000L);
        file(new File(wa, "Backups/chatsettingsbackup.db.crypt14"), 20, NOW - 50_000L);
        file(new File(wa, "Media/WhatsApp Images/IMG-1.jpg"), 500, NOW - 90_000L);
        saving = new Saving(wa, parkRoot, () -> free);
    }

    /** [size] bytes, different from every other file this test writes, timed [mtimeMs]. */
    private File file(File f, int size, long mtimeMs) throws Exception {
        f.getParentFile().mkdirs();
        fill++;
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (fill * 31 + i);
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        assertTrue(f.setLastModified(mtimeMs));
        return f;
    }

    private File backup(String name, int size, long mtimeMs) throws Exception {
        return file(new File(wa, "Databases/" + name), size, mtimeMs);
    }

    private Result save() {
        return saving.save("2.26.39.70", 263970L, SIG, INSTALLED, NOW);
    }

    private MoveRecord record() {
        return MoveRecord.readFrom(parkRoot);
    }

    private File saved(String path) {
        return new File(parkRoot, "Saved/" + path);
    }

    @Test
    public void saves_the_newest_backup_and_the_Backups_folder_with_their_times() throws Exception {
        Result r = save();
        assertTrue(r.summary, r.ok);
        assertTrue(saved("Databases/msgstore.db.crypt14").isFile());
        assertEquals(NOW - 60_000L, saved("Databases/msgstore.db.crypt14").lastModified());
        assertTrue(saved("Backups/chatsettingsbackup.db.crypt14").isFile());
        // Media is gigabytes; only step 4's park keeps it.
        assertFalse(saved("Media").exists());
        MoveRecord m = record();
        assertNotNull(m);
        assertEquals(2, m.saved.size());
        assertEquals(SIG, m.waSignatureSha256);
        assertEquals(INSTALLED, m.waFirstInstallMs);
        assertEquals(NOW, m.savedAtMs);
        assertEquals("crypt14", m.crypt);
        assertEquals("Databases/msgstore.db.crypt14", m.savedBackup().name);
        assertEquals(NOW - 60_000L, m.savedBackup().mtimeMs);
        assertEquals(Verify.sha256(new File(wa, "Databases/msgstore.db.crypt14")), m.savedBackup().sha256);
        assertFalse(new File(parkRoot, "Saved.tmp").exists());
    }

    @Test
    public void only_the_newest_of_several_backups_is_copied() throws Exception {
        backup("msgstore-2026-10-09.1.db.crypt14", 900, NOW - 86_400_000L);
        assertTrue(save().ok);
        assertFalse(saved("Databases/msgstore-2026-10-09.1.db.crypt14").exists());
        assertTrue(saved("Databases/msgstore.db.crypt14").isFile());
    }

    @Test
    public void a_backup_with_no_Backups_folder_is_saved_alone() throws Exception {
        assertTrue(Parking.deleteTree(new File(wa, "Backups")));
        Result r = save();
        assertTrue(r.summary, r.ok);
        assertEquals(1, record().saved.size());
    }

    @Test
    public void no_room_changes_nothing() {
        free = 10L;
        Result r = save();
        assertFalse(r.ok);
        assertTrue(r.neededBytes > Saving.MARGIN_BYTES);
        assertFalse(new File(parkRoot, "Saved").exists());
        assertNull(record());
    }

    @Test
    public void nothing_is_saved_without_a_backup() {
        assertTrue(Parking.deleteTree(new File(wa, "Databases")));
        assertFalse(save().ok);
        assertNull(record());
    }

    @Test
    public void the_same_backup_twice_is_not_copied_again() {
        assertTrue(save().ok);
        Result r = save();
        assertTrue(r.summary, r.ok);
        assertTrue(r.summary, r.summary.contains("already saved"));
    }

    @Test
    public void a_newer_backup_of_about_the_same_size_replaces_the_copy() throws Exception {
        assertTrue(save().ok);
        File newer = backup("msgstore.db.crypt14", 1000, NOW - 10_000L);
        Result r = save();
        assertTrue(r.summary, r.ok);
        assertEquals(NOW - 10_000L, record().savedBackup().mtimeMs);
        assertEquals(Verify.sha256(newer), Verify.sha256(saved("Databases/msgstore.db.crypt14")));
        assertFalse(new File(parkRoot, "Saved.old").exists());
    }

    @Test
    public void a_much_smaller_newer_backup_never_replaces_the_copy() throws Exception {
        assertTrue(save().ok);
        backup("msgstore.db.crypt14", 100, NOW - 10_000L);
        Result r = save();
        assertTrue(r.summary, r.summary.startsWith("Kept the saved copy"));
        assertEquals(NOW - 60_000L, record().savedBackup().mtimeMs);
        assertEquals(1000L, saved("Databases/msgstore.db.crypt14").length());
    }

    @Test
    public void a_reinstalled_WhatsApp_never_replaces_the_copy() throws Exception {
        assertTrue(save().ok);
        backup("msgstore.db.crypt14", 1000, NOW - 10_000L);
        Result r = saving.save("2.26.39.70", 263970L, SIG, INSTALLED + 1, NOW);
        assertTrue(r.summary, r.summary.startsWith("Kept the saved copy"));
        assertEquals(NOW - 60_000L, record().savedBackup().mtimeMs);
    }

    @Test
    public void a_damaged_copy_is_never_remade_from_a_reinstalled_WhatsApp() throws Exception {
        assertTrue(save().ok);
        assertTrue(saved("Backups/chatsettingsbackup.db.crypt14").delete());
        backup("msgstore.db.crypt14", 100, NOW - 10_000L);
        Result r = saving.save("2.26.39.70", 263970L, SIG, INSTALLED + 1, NOW);
        assertTrue(r.summary, r.ok);
        assertTrue(r.summary, r.summary.startsWith("Kept the saved copy"));
        assertTrue(saved("Databases/msgstore.db.crypt14").isFile());
        assertEquals(1000L, saved("Databases/msgstore.db.crypt14").length());
        assertTrue(Leftovers.previousCopies(parkRoot).isEmpty());
        assertEquals(INSTALLED, record().waFirstInstallMs);
    }

    @Test
    public void a_damaged_copy_from_the_same_install_is_set_aside_and_made_again() throws Exception {
        assertTrue(save().ok);
        assertTrue(saved("Backups/chatsettingsbackup.db.crypt14").delete());
        backup("msgstore.db.crypt14", 1000, NOW - 10_000L);
        Result r = save();
        assertTrue(r.summary, r.ok);
        assertEquals(1, Leftovers.previousCopies(parkRoot).size());
        assertTrue(new File(Leftovers.previousCopies(parkRoot).get(0),
                "Saved/Databases/msgstore.db.crypt14").isFile());
        assertEquals(NOW - 10_000L, record().savedBackup().mtimeMs);
        assertEquals(INSTALLED, record().waFirstInstallMs);
    }

    @Test
    public void a_backup_from_another_certificate_never_replaces_the_copy() throws Exception {
        assertTrue(save().ok);
        backup("msgstore.db.crypt14", 1000, NOW - 10_000L);
        Result r = saving.save("2.26.39.70", 263970L, OTHER_SIG, INSTALLED, NOW);
        assertTrue(r.summary, r.summary.startsWith("Kept the saved copy"));
        assertEquals(NOW - 60_000L, record().savedBackup().mtimeMs);
    }

    @Test
    public void a_leftover_temp_folder_is_redone_while_the_source_is_there() throws Exception {
        file(new File(parkRoot, "Saved.tmp/Databases/junk"), 5, NOW);
        Result r = save();
        assertTrue(r.summary, r.ok);
        assertFalse(new File(parkRoot, "Saved.tmp").exists());
        assertFalse(saved("Databases/junk").exists());
        assertTrue(saved("Databases/msgstore.db.crypt14").isFile());
    }

    @Test
    public void a_finished_copy_without_its_record_is_recorded() {
        // Killed between Saved.tmp -> Saved and writing move.txt.
        assertTrue(save().ok);
        assertTrue(MoveRecord.fileIn(parkRoot).delete());
        Result r = save();
        assertTrue(r.summary, r.ok);
        assertTrue(r.summary, r.summary.contains("already made"));
        assertNotNull(record());
        assertEquals("Databases/msgstore.db.crypt14", record().savedBackup().name);
        assertTrue(Leftovers.previousCopies(parkRoot).isEmpty());
    }

    @Test
    public void a_copy_without_its_record_that_no_longer_matches_is_set_aside() throws Exception {
        assertTrue(save().ok);
        assertTrue(MoveRecord.fileIn(parkRoot).delete());
        backup("msgstore.db.crypt14", 1000, NOW - 5_000L);
        assertTrue(save().ok);
        assertEquals(1, Leftovers.previousCopies(parkRoot).size());
        assertTrue(new File(Leftovers.previousCopies(parkRoot).get(0),
                "Saved/Databases/msgstore.db.crypt14").isFile());
        assertEquals(NOW - 5_000L, record().savedBackup().mtimeMs);
    }

    @Test
    public void a_replacement_whose_record_was_already_switched_is_finished() throws Exception {
        assertTrue(save().ok);
        File old = new File(parkRoot, "Saved.old");
        file(new File(old, "Databases/msgstore.db.crypt14"), 900, NOW - 600_000L);
        Result r = save();
        assertTrue(r.summary, r.ok);
        assertFalse(old.exists());
        assertTrue(saved("Databases/msgstore.db.crypt14").isFile());
    }

    @Test
    public void a_replacement_whose_record_was_not_switched_is_undone_then_redone() throws Exception {
        assertTrue(save().ok);
        File savedDir = new File(parkRoot, "Saved");
        File old = new File(parkRoot, "Saved.old");
        // The first rename of a replacement happened, and the new copy is in place,
        // but move.txt still describes the old one.
        assertTrue(savedDir.renameTo(old));
        File newer = backup("msgstore.db.crypt14", 1000, NOW - 10_000L);
        File placed = new File(savedDir, "Databases/msgstore.db.crypt14");
        placed.getParentFile().mkdirs();
        Files.copy(newer.toPath(), placed.toPath());

        Result r = save();

        assertTrue(r.summary, r.ok);
        assertEquals(NOW - 10_000L, record().savedBackup().mtimeMs);
        assertEquals(Verify.sha256(newer), Verify.sha256(saved("Databases/msgstore.db.crypt14")));
        assertFalse(old.exists());
        assertFalse(new File(parkRoot, "Saved.tmp").exists());
        assertTrue(Leftovers.previousCopies(parkRoot).isEmpty());
    }

    // --- cancel ---

    @Test
    public void cancel_removes_the_copy_and_the_record_while_WhatsApp_holds_the_backup() {
        assertTrue(save().ok);
        Result r = saving.cancel(record());
        assertTrue(r.summary, r.ok);
        assertFalse(new File(parkRoot, "Saved").exists());
        assertFalse(MoveRecord.fileIn(parkRoot).exists());
        assertTrue(new File(wa, "Databases/msgstore.db.crypt14").isFile());
    }

    @Test
    public void cancel_is_refused_once_WhatsApp_no_longer_holds_the_backup() {
        assertTrue(save().ok);
        assertTrue(Parking.deleteTree(new File(wa, "Databases")));
        Result r = saving.cancel(record());
        assertFalse(r.ok);
        assertTrue(saved("Databases/msgstore.db.crypt14").isFile());
        assertNotNull(record());
    }

    @Test
    public void cancel_is_allowed_after_WhatsApp_made_a_newer_backup() throws Exception {
        assertTrue(save().ok);
        // WhatsApp rotates: the old file gets a dated name, and a new one takes its place.
        File current = new File(wa, "Databases/msgstore.db.crypt14");
        assertTrue(current.renameTo(new File(wa, "Databases/msgstore-2026-10-10.1.db.crypt14")));
        backup("msgstore.db.crypt14", 1050, NOW - 1_000L);
        assertTrue(saving.cancel(record()).ok);
    }

    @Test
    public void cancel_never_touches_an_earlier_moves_copies() throws Exception {
        File earlier = file(new File(parkRoot, "previous-2026-10-01-1200/Saved/Databases/msgstore.db.crypt14"),
                10, NOW);
        assertTrue(save().ok);
        assertTrue(saving.cancel(record()).ok);
        assertTrue(earlier.isFile());
    }
}
