package com.patcher.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

/**
 * The record is what lets a move outlive WhatsApp being removed from Settings, so
 * each way it could be misread is its own case: a half-written file, a stranger's
 * keys, a put-back that must forget a confirmation about a WhatsApp now gone.
 */
public class MoveRecordTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SIG = "a".repeat(64);
    private static final String HASH_DB = "c".repeat(64);
    private static final String HASH_SETTINGS = "d".repeat(64);

    private static MoveRecord sample() {
        return new MoveRecord("2.26.39.70", 263970L, SIG, 1_700_000_000_000L, "crypt14",
                1_800_000_000_000L,
                Arrays.asList(
                        new ParkManifest.Db("Databases/msgstore.db.crypt14", 1_000_000L,
                                1_799_999_000_000L, HASH_DB),
                        // Written a moment after the chats by the same backup run: newer, and not the chats.
                        new ParkManifest.Db("Backups/chatsettingsbackup.db.crypt14", 2_000L,
                                1_799_999_100_000L, HASH_SETTINGS)),
                0L, MoveRecord.Source.NONE, 0L, 0L, 0L, "");
    }

    @Test
    public void a_saved_path_that_climbs_out_cannot_be_trusted() {
        String text = sample().render();
        assertNull(MoveRecord.parse(text.replace("Databases/msgstore.db.crypt14", "../msgstore.db.crypt14")));
        assertNull(MoveRecord.parse(text.replace("Databases/msgstore.db.crypt14", "/sdcard/x")));
    }

    @Test
    public void every_field_survives_a_round_trip() {
        MoveRecord r = MoveRecord.parse(sample()
                .withRestored(5L, MoveRecord.Source.SAVED)
                .withChatsConfirmed(6L)
                .withEvidence(7L, 990_000L, "e".repeat(64))
                .render());
        assertNotNull(r);
        assertEquals("2.26.39.70", r.waVersionName);
        assertEquals(263970L, r.waVersionCode);
        assertEquals(SIG, r.waSignatureSha256);
        assertEquals(1_700_000_000_000L, r.waFirstInstallMs);
        assertEquals("crypt14", r.crypt);
        assertEquals(1_800_000_000_000L, r.savedAtMs);
        assertEquals(2, r.saved.size());
        assertEquals("Backups/chatsettingsbackup.db.crypt14", r.saved.get(1).name);
        assertEquals(2_000L, r.saved.get(1).size);
        assertEquals(1_799_999_100_000L, r.saved.get(1).mtimeMs);
        assertEquals(HASH_SETTINGS, r.saved.get(1).sha256);
        assertEquals(5L, r.restoredAtMs);
        assertEquals(MoveRecord.Source.SAVED, r.restoredFrom);
        assertEquals(6L, r.chatsConfirmedAtMs);
        assertEquals(7L, r.evidenceAtMs);
        assertEquals(990_000L, r.evidenceSize);
        assertEquals("e".repeat(64), r.evidenceSha256);
    }

    @Test
    public void keys_it_does_not_know_survive_a_rewrite() {
        // An older store must never drop what a newer one wrote.
        String newer = sample().render().replace("end=1\n", "future_key=42\nend=1\n");
        MoveRecord r = MoveRecord.parse(newer);
        assertNotNull(r);
        String rewritten = r.withChatsConfirmed(9L).render();
        assertTrue(rewritten.contains("future_key=42\n"));
        assertTrue(rewritten.endsWith("end=1\n"));
        assertNotNull(MoveRecord.parse(rewritten));
    }

    @Test
    public void a_truncated_record_cannot_be_trusted() {
        String text = sample().render();
        assertNull(MoveRecord.parse(text.substring(0, text.indexOf("end=1"))));
    }

    @Test
    public void a_record_with_a_bad_signature_cannot_be_trusted() {
        assertNull(MoveRecord.parse(sample().render().replace(SIG, "null")));
    }

    @Test
    public void a_put_back_forgets_the_confirmation_and_the_proof() {
        MoveRecord r = sample().withChatsConfirmed(6L).withEvidence(7L, 1L, "e".repeat(64))
                .withRestored(8L, MoveRecord.Source.KEPT);
        assertEquals(8L, r.restoredAtMs);
        assertEquals(MoveRecord.Source.KEPT, r.restoredFrom);
        assertEquals(0L, r.chatsConfirmedAtMs);
        assertEquals(0L, r.evidenceAtMs);
        assertEquals("", r.evidenceSha256);
        assertEquals(2, r.saved.size());
    }

    @Test
    public void the_saved_backup_is_the_chats_not_a_newer_file_in_Backups() {
        assertEquals("Databases/msgstore.db.crypt14", sample().savedBackup().name);
    }

    @Test
    public void a_record_made_from_a_manifest_has_its_facts_and_nothing_saved() {
        ParkManifest m = new ParkManifest(ParkManifest.State.RESTORED, 1L, "2.26.38.74", 263807433L,
                SIG, "crypt15", 3, 30L, Arrays.asList(new ParkManifest.Db("msgstore.db.crypt15", 10L, 2L, HASH_DB)));
        MoveRecord r = MoveRecord.fromManifest(m, 0L);
        assertEquals("2.26.38.74", r.waVersionName);
        assertEquals(263807433L, r.waVersionCode);
        assertEquals(SIG, r.waSignatureSha256);
        assertEquals("crypt15", r.crypt);
        assertEquals(0L, r.savedAtMs);
        assertTrue(r.saved.isEmpty());
        assertNull(r.savedBackup());
    }

    @Test
    public void missing_and_unreadable_are_told_apart() throws Exception {
        File root = new File(tmp.getRoot(), "WAMigrate");
        assertNull(MoveRecord.readFrom(root));
        assertTrue(!MoveRecord.fileIn(root).isFile());

        MoveRecord.writeTo(root, sample());
        assertNotNull(MoveRecord.readFrom(root));

        Parking.atomicWriteText(MoveRecord.fileIn(root), "garbage");
        assertNull(MoveRecord.readFrom(root));
        assertTrue(MoveRecord.fileIn(root).isFile());
    }
}
