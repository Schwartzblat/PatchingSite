package com.patcher.migrate;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything the companion checks rather than assumes.
 *
 * The databases are hashed because they are the irreplaceable part and they are
 * small; the rest of the tree is only counted, because hashing twenty gigabytes
 * of photographs to prove a rename worked would take minutes and prove nothing
 * a count does not.
 */
final class Verify {

    private Verify() {
    }

    public static final class Tally {
        public final int files;
        public final long bytes;

        Tally(int files, long bytes) {
            this.files = files;
            this.bytes = bytes;
        }
    }

    public static String sha256(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("no SHA-256", e);
        }
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new FileInputStream(f)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                md.update(buf, 0, r);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    /**
     * A walk, not a sum of lengths: a file that vanishes mid-walk is skipped
     * rather than allowed to abort a park. WhatsApp is still running while this
     * happens and rotates its own files.
     */
    public static Tally tally(File dir) {
        int files = 0;
        long bytes = 0;
        File[] children = dir.listFiles();
        if (children == null) {
            return new Tally(0, 0);
        }
        for (File c : children) {
            if (c.isDirectory()) {
                Tally sub = tally(c);
                files += sub.files;
                bytes += sub.bytes;
            } else if (c.isFile()) {
                files++;
                bytes += c.length();
            }
        }
        return new Tally(files, bytes);
    }

    public static List<ParkManifest.Db> describeDatabases(File dbDir) throws IOException {
        File[] children = dbDir.listFiles();
        if (children == null) {
            return Collections.emptyList();
        }
        List<ParkManifest.Db> out = new ArrayList<>();
        for (File c : children) {
            if (c.isFile()) {
                out.add(new ParkManifest.Db(c.getName(), c.length(), c.lastModified(), sha256(c)));
            }
        }
        return out;
    }

    /**
     * The crypt version of the newest backup, because that is the one WhatsApp
     * will restore. crypt15 means end-to-end encrypted backups are on, the
     * server will not return the key, and the user needs their own 64-digit key
     * -- which they have to be told before they uninstall anything.
     */
    public static String cryptOf(List<ParkManifest.Db> databases) {
        ParkManifest.Db newest = newestCrypt(databases);
        if (newest == null) {
            return "none";
        }
        int at = newest.name.lastIndexOf(".crypt");
        return newest.name.substring(at + 1);
    }

    /**
     * The backup WhatsApp would restore from [databases]: the .crypt entry with the
     * newest recorded time. Null when there is none. By recorded time, never the
     * file's: a copy's own time is when it was copied.
     */
    static ParkManifest.Db newestCrypt(List<ParkManifest.Db> databases) {
        ParkManifest.Db newest = null;
        for (ParkManifest.Db d : databases) {
            if (d.name.contains(".crypt") && (newest == null || d.mtimeMs > newest.mtimeMs)) {
                newest = d;
            }
        }
        return newest;
    }

    /**
     * The newest backup by name and mtime alone, or null when there is none.
     *
     * No hash, unlike describeDatabases: this feeds status(), which a screen calls
     * on every resume, and the databases run to tens of megabytes. Its sha256 is
     * therefore null, and it must never reach a manifest.
     */
    public static ParkManifest.Db newestBackup(File dbDir) {
        File[] children = dbDir.listFiles();
        if (children == null) {
            return null;
        }
        ParkManifest.Db newest = null;
        for (File c : children) {
            if (c.isFile() && c.getName().contains(".crypt")
                    && (newest == null || c.lastModified() > newest.mtimeMs)) {
                newest = new ParkManifest.Db(c.getName(), c.length(), c.lastModified(), null);
            }
        }
        return newest;
    }

    /**
     * The tree as a count and a size, not a hash. A difference here means
     * something else wrote into the folder between the park and the put-back --
     * worth telling the user, never worth refusing over, because the databases
     * are what the hashes already proved.
     */
    public static String compareTree(ParkManifest manifest, File tree) {
        Tally now = tally(tree);
        if (now.files == manifest.treeFiles && now.bytes == manifest.treeBytes) {
            return null;
        }
        return "The folder now holds " + now.files + " files and " + now.bytes
                + " bytes, against " + manifest.treeFiles + " and " + manifest.treeBytes
                + " when it was parked. Something else wrote here in between.";
    }

    public static List<String> compare(ParkManifest manifest, File dbDir) {
        return compareFiles(manifest.databases, dbDir);
    }

    /**
     * [expected] against the files under [root], by each entry's path: present, the
     * same size, the same SHA-256. Paths may hold a folder ("Databases/x"), which is
     * how a saved copy records its files.
     */
    public static List<String> compareFiles(List<ParkManifest.Db> expected, File root) {
        List<String> problems = new ArrayList<>();
        for (ParkManifest.Db want : expected) {
            File actual = new File(root, want.name);
            if (!actual.isFile()) {
                problems.add(want.name + " is missing after the move");
                continue;
            }
            if (actual.length() != want.size) {
                problems.add(want.name + " changed size: expected " + want.size
                        + " bytes, found " + actual.length());
                continue;
            }
            try {
                String got = sha256(actual);
                if (!got.equals(want.sha256)) {
                    problems.add(want.name + " does not match its recorded SHA-256");
                }
            } catch (IOException e) {
                problems.add(want.name + " could not be read back: " + e.getMessage());
            }
        }
        return problems;
    }

    /**
     * Present and the right size, without hashing: for status(), which a screen
     * calls on every resume. False when nothing is expected, so an empty list never
     * reads as a good copy.
     */
    static boolean sizesMatch(List<ParkManifest.Db> expected, File root) {
        if (expected.isEmpty()) {
            return false;
        }
        for (ParkManifest.Db want : expected) {
            File actual = new File(root, want.name);
            if (!actual.isFile() || actual.length() != want.size) {
                return false;
            }
        }
        return true;
    }
}
