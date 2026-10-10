package com.patcher.migrate;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What the engine does with files in WAMigrate that no current record describes:
 * an earlier move's manifest and kept databases, a copy whose record was lost.
 * They are renamed into previous-<yyyy-MM-dd-HHmm>, never deleted. Nothing here can
 * tell whether they are somebody's only copy of their chats, so they wait for the
 * user's own "Remove the spare copy" at the end of a move (Finishing.finish).
 */
final class Leftovers {

    static final String PREFIX = "previous-";

    private Leftovers() {
    }

    /**
     * Moves each of [items] that exists into a new previous-<date> folder in
     * [parkRoot], and returns that folder; null when none existed. Renames only, all
     * within WAMigrate, so nothing is copied and nothing can be half-moved.
     */
    static File setAside(File parkRoot, long nowMs, File... items) throws IOException {
        List<File> present = new ArrayList<>();
        for (File item : items) {
            if (item != null && item.exists()) {
                present.add(item);
            }
        }
        if (present.isEmpty()) {
            return null;
        }
        String base = PREFIX + new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(new Date(nowMs));
        File folder = new File(parkRoot, base);
        for (int n = 2; folder.exists(); n++) {
            folder = new File(parkRoot, base + "-" + n);
        }
        if (!folder.mkdirs()) {
            throw new IOException("Could not create " + folder.getAbsolutePath());
        }
        for (File item : present) {
            File into = new File(folder, item.getName());
            if (!item.renameTo(into)) {
                throw new IOException("Could not move " + item.getAbsolutePath() + " into "
                        + folder.getAbsolutePath());
            }
        }
        return folder;
    }

    /** The set-aside folders in [parkRoot], oldest name first. */
    static List<File> previousCopies(File parkRoot) {
        List<File> out = new ArrayList<>();
        File[] children = parkRoot.listFiles();
        if (children == null) {
            return out;
        }
        Arrays.sort(children);
        for (File c : children) {
            if (c.isDirectory() && c.getName().startsWith(PREFIX)) {
                out.add(c);
            }
        }
        return out;
    }
}
