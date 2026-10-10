package com.patcher.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

/**
 * A WhatsApp removed by hand, or removed again after a put-back, deletes its
 * folder. These are the put-backs from what is left: copied, never moved, so the
 * copy outlives the next removal too.
 */
public class PutBackFromCopyTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SIG = "a".repeat(64);
    private static final long NOW = 1_800_000_000_000L;

    private File wa;
    private File parkRoot;
    private Saving saving;

    @Before
    public void setUp() throws Exception {
        wa = new File(tmp.getRoot(), "media/com.whatsapp/WhatsApp");
        parkRoot = new File(tmp.getRoot(), "WAMigrate");
        write(new File(wa, "Databases/msgstore.db.crypt14"), 1000, NOW - 60_000L, 1);
        write(new File(wa, "Backups/chatsettingsbackup.db.crypt14"), 20, NOW - 50_000L, 2);
        write(new File(wa, "Media/WhatsApp Images/IMG-1.jpg"), 500, NOW - 90_000L, 3);
        saving = new Saving(wa, parkRoot, () -> Long.MAX_VALUE);
    }

    private static void write(File f, int size, long mtimeMs, int seed) throws Exception {
        f.getParentFile().mkdirs();
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 7 + seed * 31);
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        assertTrue(f.setLastModified(mtimeMs));
    }

    /** What Settings > Uninstall does: all of Android/media/com.whatsapp goes. */
    private void removeWhatsApp() {
        assertTrue(Parking.deleteTree(wa.getParentFile()));
    }

    private Result saveAndRemove() {
        Result r = saving.save("2.26.39.70", 263970L, SIG, 1L, NOW);
        assertTrue(r.summary, r.ok);
        removeWhatsApp();
        return r;
    }

    @Test
    public void the_saved_copy_goes_back_with_its_times_and_stays_saved() throws Exception {
        String hash = Verify.sha256(new File(wa, "Databases/msgstore.db.crypt14"));
        saveAndRemove();

        Result r = saving.putBack(MoveRecord.Source.SAVED, null, MoveRecord.readFrom(parkRoot));

        assertTrue(r.summary, r.ok);
        File db = new File(wa, "Databases/msgstore.db.crypt14");
        assertEquals(hash, Verify.sha256(db));
        assertEquals(NOW - 60_000L, db.lastModified());
        assertTrue(new File(wa, "Backups/chatsettingsbackup.db.crypt14").isFile());
        assertFalse(new File(wa, "Media").exists());
        assertTrue(new File(parkRoot, "Saved/Databases/msgstore.db.crypt14").isFile());
        assertFalse(new File(wa.getParentFile(), Saving.RESTORING).exists());
    }

    @Test
    public void the_kept_copy_goes_back_into_Databases_with_the_parked_time() {
        Parking parking = new Parking(wa, parkRoot);
        assertTrue(parking.park("2.26.39.70", 263970L, SIG).ok);
        assertTrue(parking.putBack().ok);
        ParkManifest m = parking.readManifest();
        removeWhatsApp();

        Result r = saving.putBack(MoveRecord.Source.KEPT, m, null);

        assertTrue(r.summary, r.ok);
        File db = new File(wa, "Databases/msgstore.db.crypt14");
        assertTrue(db.isFile());
        assertEquals(NOW - 60_000L, db.lastModified());
        assertTrue(new File(parkRoot, "Databases-kept/msgstore.db.crypt14").isFile());
    }

    @Test
    public void a_copy_that_no_longer_matches_its_record_is_refused() throws Exception {
        saveAndRemove();
        write(new File(parkRoot, "Saved/Databases/msgstore.db.crypt14"), 1000, NOW, 9);

        Result r = saving.putBack(MoveRecord.Source.SAVED, null, MoveRecord.readFrom(parkRoot));

        assertFalse(r.ok);
        assertFalse(wa.exists());
    }

    @Test
    public void nothing_is_merged_into_a_folder_that_is_not_empty() {
        assertTrue(saving.save("2.26.39.70", 263970L, SIG, 1L, NOW).ok);
        Result r = saving.putBack(MoveRecord.Source.SAVED, null, MoveRecord.readFrom(parkRoot));
        assertFalse(r.ok);
        assertTrue(r.summary, r.summary.contains("in the way"));
    }

    @Test
    public void a_half_built_folder_from_an_interrupted_put_back_is_rebuilt() throws Exception {
        saveAndRemove();
        write(new File(wa.getParentFile(), Saving.RESTORING + "/Databases/partial"), 10, NOW, 4);

        Result r = saving.putBack(MoveRecord.Source.SAVED, null, MoveRecord.readFrom(parkRoot));

        assertTrue(r.summary, r.ok);
        assertFalse(new File(wa.getParentFile(), Saving.RESTORING).exists());
        assertFalse(new File(wa, "Databases/partial").exists());
        assertTrue(new File(wa, "Databases/msgstore.db.crypt14").isFile());
    }

    @Test
    public void with_no_copy_there_is_nothing_to_put_back() {
        removeWhatsApp();
        assertFalse(saving.putBack(MoveRecord.Source.SAVED, null, null).ok);
        assertFalse(saving.putBack(MoveRecord.Source.KEPT, null, null).ok);
    }
}
