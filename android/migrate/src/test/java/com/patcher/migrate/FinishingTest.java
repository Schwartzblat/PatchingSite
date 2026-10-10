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
import java.util.List;

/**
 * The end of a move. The proof has to be something only a WhatsApp that really
 * restored the chats leaves behind, and the one delete has to leave the records
 * last, so a delete that stops half-way still describes what is left.
 */
public class FinishingTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SIG = "a".repeat(64);
    private static final long NOW = 1_800_000_000_000L;
    private static final long RESTORED_AT = NOW - 30_000L;

    private File wa;
    private File parkRoot;
    private Finishing finishing;
    private int fill;

    @Before
    public void setUp() throws Exception {
        wa = new File(tmp.getRoot(), "media/com.whatsapp/WhatsApp");
        parkRoot = new File(tmp.getRoot(), "WAMigrate");
        file(new File(wa, "Databases/msgstore.db.crypt14"), 1000, NOW - 60_000L);
        Saving saving = new Saving(wa, parkRoot, () -> Long.MAX_VALUE);
        finishing = new Finishing(wa, parkRoot);
        // The move so far: saved, WhatsApp removed by hand, put back from the copy.
        assertTrue(saving.save("2.26.39.70", 263970L, SIG, 1L, NOW).ok);
        assertTrue(Parking.deleteTree(wa.getParentFile()));
        assertTrue(saving.putBack(MoveRecord.Source.SAVED, null, MoveRecord.readFrom(parkRoot)).ok);
        MoveRecord.writeTo(parkRoot,
                MoveRecord.readFrom(parkRoot).withRestored(RESTORED_AT, MoveRecord.Source.SAVED));
    }

    private void file(File f, int size, long mtimeMs) throws Exception {
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
    }

    private ParkManifest.Db evidence() throws Exception {
        MoveRecord r = MoveRecord.readFrom(parkRoot);
        return finishing.findEvidence(r, null, r.savedBackup(), r.restoredAtMs);
    }

    /** The new WhatsApp's own backup: new bytes in the same file, as WhatsApp writes it. */
    private void newWhatsAppBacksUp(int size, long mtimeMs) throws Exception {
        file(new File(wa, "Databases/msgstore.db.crypt14"), size, mtimeMs);
    }

    @Test
    public void a_new_backup_of_about_the_same_size_after_the_put_back_is_proof() throws Exception {
        newWhatsAppBacksUp(990, NOW - 10_000L);
        ParkManifest.Db proof = evidence();
        assertNotNull(proof);
        assertEquals(990L, proof.size);
        assertEquals(NOW - 10_000L, proof.mtimeMs);
    }

    @Test
    public void a_backup_from_before_the_put_back_is_not_proof() throws Exception {
        newWhatsAppBacksUp(1000, RESTORED_AT - 1);
        assertNull(evidence());
    }

    @Test
    public void a_backup_from_the_same_moment_as_the_put_back_is_not_proof() throws Exception {
        newWhatsAppBacksUp(1000, RESTORED_AT);
        assertNull(evidence());
    }

    @Test
    public void the_put_back_file_under_a_new_date_is_not_proof() throws Exception {
        assertTrue(new File(wa, "Databases/msgstore.db.crypt14").setLastModified(NOW - 10_000L));
        assertNull(evidence());
    }

    @Test
    public void a_much_smaller_backup_is_not_proof() throws Exception {
        newWhatsAppBacksUp(500, NOW - 10_000L);
        assertNull(evidence());
    }

    @Test
    public void finish_removes_everything_including_earlier_copies() throws Exception {
        file(new File(parkRoot, "previous-2026-10-01-1200/Databases-kept/msgstore.db.crypt14"), 10, NOW);
        file(new File(parkRoot, "Databases-kept/msgstore.db.crypt14"), 10, NOW);
        Parking.atomicWriteText(new File(parkRoot, ParkManifest.FILE_NAME), "state=RESTORED\n");

        Result r = finishing.finish();

        assertTrue(r.summary, r.ok);
        assertFalse(parkRoot.exists());
        assertTrue(new File(wa, "Databases/msgstore.db.crypt14").isFile());
    }

    @Test
    public void a_finish_that_fails_part_way_keeps_both_records() throws Exception {
        Parking.atomicWriteText(new File(parkRoot, ParkManifest.FILE_NAME), "state=RESTORED\n");
        File locked = new File(parkRoot, "Saved/Databases");
        // On Linux a file cannot be removed from a folder you cannot write to.
        assertTrue(locked.setWritable(false));
        try {
            Result r = finishing.finish();
            assertFalse(r.summary, r.ok);
            assertTrue(new File(parkRoot, ParkManifest.FILE_NAME).isFile());
            assertTrue(MoveRecord.fileIn(parkRoot).isFile());
        } finally {
            locked.setWritable(true);
        }
    }

    @Test
    public void spare_copies_lists_each_folder_with_its_size() throws Exception {
        file(new File(parkRoot, "previous-2026-10-01-1200/Saved/Databases/msgstore.db.crypt14"), 10, NOW);

        List<SpareCopy> copies = finishing.spareCopies();

        assertEquals(2, copies.size());
        assertEquals("Saved", copies.get(0).name);
        assertFalse(copies.get(0).isEarlier());
        assertEquals(1000L, copies.get(0).bytes);
        assertEquals("previous-2026-10-01-1200", copies.get(1).name);
        assertTrue(copies.get(1).isEarlier());
        assertEquals(10L, copies.get(1).bytes);
    }
}
