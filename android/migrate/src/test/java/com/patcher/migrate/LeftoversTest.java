package com.patcher.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** An earlier move's files may be somebody's only copy of their chats: set aside, never deleted. */
public class LeftoversTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[]{1, 2, 3});
        }
    }

    private static long at(String when) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(when).getTime();
    }

    @Test
    public void items_go_into_a_dated_folder_and_nothing_is_deleted() throws Exception {
        File root = tmp.newFolder("WAMigrate");
        File manifest = new File(root, "manifest.txt");
        File kept = new File(root, "Databases-kept/msgstore.db.crypt14");
        write(manifest);
        write(kept);

        File folder = Leftovers.setAside(root, at("2026-10-10 01:57"), manifest, kept.getParentFile(),
                new File(root, "not-there"));

        assertEquals("previous-2026-10-10-0157", folder.getName());
        assertTrue(new File(folder, "manifest.txt").isFile());
        assertTrue(new File(folder, "Databases-kept/msgstore.db.crypt14").isFile());
        assertFalse(manifest.exists());
        assertFalse(kept.exists());
    }

    @Test
    public void a_second_set_aside_in_the_same_minute_gets_its_own_folder() throws Exception {
        File root = tmp.newFolder("WAMigrate");
        write(new File(root, "a.txt"));
        write(new File(root, "b.txt"));
        long when = at("2026-10-10 01:57");

        File first = Leftovers.setAside(root, when, new File(root, "a.txt"));
        File second = Leftovers.setAside(root, when, new File(root, "b.txt"));

        assertEquals("previous-2026-10-10-0157", first.getName());
        assertEquals("previous-2026-10-10-0157-2", second.getName());
        assertEquals(2, Leftovers.previousCopies(root).size());
    }

    @Test
    public void nothing_to_set_aside_makes_no_folder() throws Exception {
        File root = tmp.newFolder("WAMigrate");
        assertNull(Leftovers.setAside(root, at("2026-10-10 01:57"), new File(root, "not-there")));
        assertTrue(Leftovers.previousCopies(root).isEmpty());
    }

    @Test
    public void a_cancelled_move_is_set_aside_whole_and_earlier_ones_stay() throws Exception {
        File root = tmp.newFolder("WAMigrate");
        for (String name : new String[]{"Saved/Databases/msgstore.db.crypt14", "Saved.tmp/x", "Saved.old/y",
                "Databases-kept/msgstore.db.crypt14", "manifest.txt", "move.txt"}) {
            write(new File(root, name));
        }
        File earlier = new File(root, "previous-2026-10-01-1200/Saved/Databases/msgstore.db.crypt14");
        write(earlier);

        File folder = Leftovers.setAsideMove(root, at("2026-10-24 17:00"));

        assertEquals("previous-2026-10-24-1700", folder.getName());
        for (String name : new String[]{"Saved/Databases/msgstore.db.crypt14", "Saved.tmp/x", "Saved.old/y",
                "Databases-kept/msgstore.db.crypt14", "manifest.txt", "move.txt"}) {
            assertTrue(name, new File(folder, name).isFile());
            assertFalse(name, new File(root, name).exists());
        }
        assertTrue(earlier.isFile());
        assertEquals(2, Leftovers.previousCopies(root).size());
    }
}
