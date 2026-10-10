package com.patcher.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;

/**
 * Reporting a good restore over a bad one is the failure this class exists to
 * prevent, so a mismatch has to be detected for every field that can drift:
 * contents, size, and a file that has gone missing.
 */
public class VerifyTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f, byte[] bytes) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
    }

    @Test
    public void sha256_matches_the_known_digest_of_abc() throws Exception {
        File f = tmp.newFile("abc.bin");
        write(f, new byte[]{'a', 'b', 'c'});
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Verify.sha256(f));
    }

    @Test
    public void tally_counts_files_and_bytes_through_nested_and_hidden_dirs() throws Exception {
        File root = tmp.newFolder("tree");
        write(new File(root, "Media/a.jpg"), new byte[100]);
        write(new File(root, "Media/sub/b.jpg"), new byte[200]);
        // .Shared is one of WhatsApp's hidden children and must be counted
        write(new File(root, ".Shared/c.bin"), new byte[50]);
        Verify.Tally t = Verify.tally(root);
        assertEquals(3, t.files);
        assertEquals(350L, t.bytes);
    }

    @Test
    public void tally_of_a_missing_directory_is_zero_rather_than_a_throw() {
        Verify.Tally t = Verify.tally(new File(tmp.getRoot(), "nope"));
        assertEquals(0, t.files);
        assertEquals(0L, t.bytes);
    }

    @Test
    public void describeDatabases_records_every_backup_it_finds() throws Exception {
        File dbs = tmp.newFolder("Databases");
        write(new File(dbs, "msgstore.db.crypt14"), new byte[10]);
        write(new File(dbs, "msgstore-2026-09-30.1.db.crypt14"), new byte[20]);
        List<ParkManifest.Db> described = Verify.describeDatabases(dbs);
        assertEquals(2, described.size());
    }

    @Test
    public void cryptOf_reports_the_newest_backups_extension() throws Exception {
        // A .crypt15 means end-to-end encrypted backups are on and WhatsApp's
        // servers will not hand the key back, so the newest file decides -- not
        // whichever happens to sort first. Put it first to test that.
        List<ParkManifest.Db> dbs = java.util.Arrays.asList(
                new ParkManifest.Db("msgstore.db.crypt15", 20L, 9000L, "bb"),
                new ParkManifest.Db("msgstore-2026-09-01.1.db.crypt14", 10L, 1000L, "aa"));
        assertEquals("crypt15", Verify.cryptOf(dbs));
    }

    @Test
    public void cryptOf_an_empty_list_is_none() {
        assertEquals("none", Verify.cryptOf(Collections.<ParkManifest.Db>emptyList()));
    }

    @Test
    public void compareTree_is_silent_when_the_count_and_size_still_match() throws Exception {
        File root = tmp.newFolder("t1");
        write(new File(root, "Media/a.jpg"), new byte[100]);
        ParkManifest m = new ParkManifest(ParkManifest.State.PARKED, 1L, "v", 1L, "s", "none",
                1, 100L, Collections.<ParkManifest.Db>emptyList());
        org.junit.Assert.assertNull(Verify.compareTree(m, root));
    }

    @Test
    public void compareTree_reports_a_folder_something_else_wrote_into() throws Exception {
        File root = tmp.newFolder("t2");
        write(new File(root, "Media/a.jpg"), new byte[100]);
        ParkManifest m = new ParkManifest(ParkManifest.State.PARKED, 1L, "v", 1L, "s", "none",
                1, 100L, Collections.<ParkManifest.Db>emptyList());
        write(new File(root, "Media/b.jpg"), new byte[7]);
        String note = Verify.compareTree(m, root);
        assertTrue(note, note != null && note.contains("wrote here in between"));
    }

    @Test
    public void compare_is_silent_when_every_database_still_matches() throws Exception {
        File dbs = tmp.newFolder("Databases");
        File f = new File(dbs, "msgstore.db.crypt14");
        write(f, new byte[]{'a', 'b', 'c'});
        ParkManifest m = new ParkManifest(ParkManifest.State.PARKED, 1L, "v", 1L, "s", "crypt14",
                1, 3L, Verify.describeDatabases(dbs));
        assertTrue(Verify.compare(m, dbs).isEmpty());
    }

    @Test
    public void compare_reports_changed_content() throws Exception {
        File dbs = tmp.newFolder("Databases");
        File f = new File(dbs, "msgstore.db.crypt14");
        write(f, new byte[]{'a', 'b', 'c'});
        ParkManifest m = new ParkManifest(ParkManifest.State.PARKED, 1L, "v", 1L, "s", "crypt14",
                1, 3L, Verify.describeDatabases(dbs));
        write(f, new byte[]{'x', 'y', 'z'});
        List<String> problems = Verify.compare(m, dbs);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("msgstore.db.crypt14"));
    }

    @Test
    public void compare_reports_a_missing_file() throws Exception {
        File dbs = tmp.newFolder("Databases");
        File f = new File(dbs, "msgstore.db.crypt14");
        write(f, new byte[]{'a'});
        ParkManifest m = new ParkManifest(ParkManifest.State.PARKED, 1L, "v", 1L, "s", "crypt14",
                1, 1L, Verify.describeDatabases(dbs));
        assertTrue(f.delete());
        List<String> problems = Verify.compare(m, dbs);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("missing"));
    }

    @Test
    public void tally_skips_an_entry_that_is_neither_a_file_nor_a_directory() throws Exception {
        File root = tmp.newFolder("rotated");
        write(new File(root, "real.jpg"), new byte[64]);
        // A file WhatsApp rotated away mid-walk is neither a file nor a directory by the
        // time listFiles hands it over. A broken symlink is the same shape, deterministically.
        java.nio.file.Files.createSymbolicLink(
                new File(root, "gone.jpg").toPath(), new File(root, "nothere").toPath());
        Verify.Tally t = Verify.tally(root);
        assertEquals(1, t.files);
        assertEquals(64L, t.bytes);
    }

    @Test
    public void compare_reports_changed_size() throws Exception {
        File dbs = tmp.newFolder("Databases");
        File f = new File(dbs, "msgstore.db.crypt14");
        write(f, new byte[]{'a', 'b', 'c'});
        ParkManifest m = new ParkManifest(ParkManifest.State.PARKED, 1L, "v", 1L, "s", "crypt14",
                1, 3L, Verify.describeDatabases(dbs));
        write(f, new byte[]{'a', 'b', 'c', 'd', 'e', 'f'});
        List<String> problems = Verify.compare(m, dbs);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("size"));
    }
}
