package com.patcher.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;

/**
 * Cancel at any step but the last (2026-10-24): the backup goes back where WhatsApp
 * looks for it, and then the move ends. Each case reads its facts from the temp
 * folders the way Migration reads the phone's, so the engine's own decision is what
 * runs, and each ends by reading them again.
 */
public class CancellingTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SIG = "a".repeat(64);
    private static final String OTHER_SIG = "b".repeat(64);
    private static final long INSTALLED = 1_700_000_000_000L;
    private static final long NOW = 1_800_000_000_000L;

    private File wa;
    private File parkRoot;
    private Parking parking;
    private Saving saving;
    private Cancelling cancelling;

    @Before
    public void setUp() throws Exception {
        wa = new File(tmp.getRoot(), "media/com.whatsapp/WhatsApp");
        parkRoot = new File(tmp.getRoot(), "WAMigrate");
        write(new File(wa, "Databases/msgstore.db.crypt14"), "the chats", NOW - 60_000L);
        write(new File(wa, "Backups/wa.db.crypt14"), "contacts", NOW - 60_000L);
        write(new File(wa, "Media/WhatsApp Images/IMG-1.jpg"), "photo one", NOW - 90_000L);
        parking = new Parking(wa, parkRoot);
        saving = new Saving(wa, parkRoot, () -> Long.MAX_VALUE);
        cancelling = new Cancelling(parkRoot, parking, saving);
    }

    private static void write(File f, String text, long mtimeMs) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
        }
        assertTrue(f.setLastModified(mtimeMs));
    }

    private static String text(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), "UTF-8");
    }

    /** The phone as Migration reads it, with the original WhatsApp installed or not. */
    private Facts facts(boolean originalInstalled) {
        return Migration.facts(wa, parkRoot, parking, true, originalInstalled, false,
                originalInstalled ? "2.26.40.72" : null, originalInstalled ? 264007233L : 0L,
                originalInstalled ? SIG : null, originalInstalled ? INSTALLED : 0L);
    }

    /** Another WhatsApp, newer than the backup: ours, or the Play Store's on the way back. */
    private Facts withOther() {
        return Migration.facts(wa, parkRoot, parking, true, true, false, "2.26.41.78", 264107833L,
                OTHER_SIG, NOW);
    }

    private void saveAndPark() {
        assertTrue(saving.save("2.26.40.72", 264007233L, SIG, INSTALLED, NOW - 30_000L).ok);
        assertTrue(parking.park("2.26.40.72", 264007233L, SIG).ok);
    }

    private Result cancel(Facts f) {
        return cancelling.cancel(f, NOW);
    }

    @Test
    public void at_the_start_the_copy_and_the_record_go() {
        assertTrue(saving.save("2.26.40.72", 264007233L, SIG, INSTALLED, NOW - 30_000L).ok);
        Result r = cancel(facts(true));
        assertTrue(r.summary, r.ok);
        assertFalse(parkRoot.exists());
        assertEquals(Step.BACK_UP_AND_PARK, MigrationState.of(facts(true)).step);
    }

    @Test
    public void parked_and_the_uninstall_declined_puts_the_folder_back_whole() throws Exception {
        saveAndPark();
        assertEquals(Step.UNINSTALL_WHATSAPP, MigrationState.of(facts(true)).step);

        Result r = cancel(facts(true));

        assertTrue(r.summary, r.ok);
        assertEquals("the chats", text(new File(wa, "Databases/msgstore.db.crypt14")));
        assertEquals("photo one", text(new File(wa, "Media/WhatsApp Images/IMG-1.jpg")));
        // Nothing of the move is left: WhatsApp holds the very backup that was saved.
        assertFalse(parkRoot.exists());
        Facts after = facts(true);
        assertEquals(Step.BACK_UP_AND_PARK, MigrationState.of(after).step);
        assertNull(after.record);
        assertNull(after.manifest);
    }

    @Test
    public void parked_and_whatsapp_used_since_merges_and_keeps_the_newer_files() throws Exception {
        // The owner's Pixel on 2026-10-24: parked on the 22nd, and WhatsApp kept running.
        saveAndPark();
        write(new File(wa, "Databases/msgstore.db.crypt14"), "the chats and two more days", NOW);
        write(new File(wa, "Media/WhatsApp Images/IMG-2.jpg"), "photo two", NOW);
        assertEquals(Step.UNINSTALL_WHATSAPP, MigrationState.of(facts(true)).step);

        Result r = cancel(facts(true));

        assertTrue(r.summary, r.ok);
        assertEquals("the chats and two more days", text(new File(wa, "Databases/msgstore.db.crypt14")));
        assertEquals("photo one", text(new File(wa, "Media/WhatsApp Images/IMG-1.jpg")));
        assertEquals("photo two", text(new File(wa, "Media/WhatsApp Images/IMG-2.jpg")));
        assertEquals("contacts", text(new File(wa, "Backups/wa.db.crypt14")));
        // The parked backup the newer one replaced is kept aside, not deleted.
        List<File> previous = Leftovers.previousCopies(parkRoot);
        assertEquals(1, previous.size());
        assertEquals("the chats", text(new File(previous.get(0), "WhatsApp/Databases/msgstore.db.crypt14")));
        Facts after = facts(true);
        assertEquals(Step.BACK_UP_AND_PARK, MigrationState.of(after).step);
        assertNull(after.record);
        assertNull(after.manifest);
    }

    @Test
    public void with_the_original_gone_the_backup_goes_back_and_the_copies_are_set_aside() throws Exception {
        saveAndPark();
        assertEquals(Step.PUT_BACK, MigrationState.of(facts(false)).step);

        Result r = cancel(facts(false));

        assertTrue(r.summary, r.ok);
        assertEquals("the chats", text(new File(wa, "Databases/msgstore.db.crypt14")));
        assertEquals("photo one", text(new File(wa, "Media/WhatsApp Images/IMG-1.jpg")));
        List<File> previous = Leftovers.previousCopies(parkRoot);
        assertEquals(1, previous.size());
        assertTrue(new File(previous.get(0), "Saved/Databases/msgstore.db.crypt14").isFile());
        assertTrue(new File(previous.get(0), MoveRecord.FILE_NAME).isFile());
        assertTrue(new File(previous.get(0), ParkManifest.FILE_NAME).isFile());
        Facts after = facts(false);
        assertEquals(Step.NOTHING_TO_MIGRATE, MigrationState.of(after).step);
        assertNull(after.record);
        assertNull(after.manifest);
    }

    @Test
    public void removed_by_hand_the_saved_copy_goes_back_and_is_set_aside() throws Exception {
        assertTrue(saving.save("2.26.40.72", 264007233L, SIG, INSTALLED, NOW - 30_000L).ok);
        assertTrue(Parking.deleteTree(wa.getParentFile()));
        assertEquals(Step.PUT_BACK, MigrationState.of(facts(false)).step);

        Result r = cancel(facts(false));

        assertTrue(r.summary, r.ok);
        assertEquals("the chats", text(new File(wa, "Databases/msgstore.db.crypt14")));
        assertEquals(Step.NOTHING_TO_MIGRATE, MigrationState.of(facts(false)).step);
        assertEquals(1, Leftovers.previousCopies(parkRoot).size());
    }

    @Test
    public void while_the_new_whatsapp_restores_the_move_just_ends() throws Exception {
        saveAndPark();
        assertTrue(Migration.putBackFrom(facts(false), parkRoot, parking, saving, NOW).ok);
        assertEquals(Step.VERIFY_AND_RESTORE, MigrationState.of(withOther()).step);

        Result r = cancel(withOther());

        assertTrue(r.summary, r.ok);
        assertEquals("the chats", text(new File(wa, "Databases/msgstore.db.crypt14")));
        assertEquals(1, Leftovers.previousCopies(parkRoot).size());
        assertNull(withOther().record);
        assertNull(withOther().manifest);
    }

    @Test
    public void a_proven_move_is_finished_not_cancelled() throws Exception {
        saveAndPark();
        assertTrue(Migration.putBackFrom(facts(false), parkRoot, parking, saving, NOW).ok);
        MoveRecord r = MoveRecord.readFrom(parkRoot);
        MoveRecord.writeTo(parkRoot, r.withEvidence(NOW + 2, 100L, "e".repeat(64)).withChatsConfirmed(NOW + 3));
        assertEquals(Step.DONE, MigrationState.of(withOther()).step);

        Result result = cancel(withOther());

        assertFalse(result.ok);
        assertTrue(MoveRecord.fileIn(parkRoot).isFile());
        assertTrue(Leftovers.previousCopies(parkRoot).isEmpty());
    }

    @Test
    public void a_cancel_stopped_before_the_manifest_went_is_finished() throws Exception {
        saveAndPark();
        // Parking.unpark's rename back happened; removing the manifest did not.
        assertTrue(new File(parkRoot, "WhatsApp").renameTo(wa));
        assertEquals(Step.UNINSTALL_WHATSAPP, MigrationState.of(facts(true)).step);

        Result r = cancel(facts(true));

        assertTrue(r.summary, r.ok);
        assertEquals("photo one", text(new File(wa, "Media/WhatsApp Images/IMG-1.jpg")));
        assertFalse(parkRoot.exists());
    }

    @Test
    public void a_whatsapp_installed_again_after_the_park_is_not_merged_into() throws Exception {
        saveAndPark();
        write(new File(wa, "Databases/msgstore.db.crypt14"), "empty", NOW);
        // The same certificate, first installed after the park: a fresh install.
        Facts reinstalled = Migration.facts(wa, parkRoot, parking, true, true, false, "2.26.40.72",
                264007233L, SIG, NOW);
        assertFalse(MigrationState.of(reinstalled).canCancel);

        assertFalse(cancel(reinstalled).ok);

        assertEquals("the chats", text(new File(parkRoot, "WhatsApp/Databases/msgstore.db.crypt14")));
        assertTrue(new File(parkRoot, "Saved/Databases/msgstore.db.crypt14").isFile());
        assertEquals("empty", text(new File(wa, "Databases/msgstore.db.crypt14")));
    }
}
