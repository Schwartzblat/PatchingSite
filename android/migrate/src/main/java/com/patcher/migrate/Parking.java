package com.patcher.migrate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The two moves, and nothing that knows what a Context is -- the directories
 * arrive as arguments so this is testable on a JVM and reusable by the app store
 * app without change.
 *
 * Both moves are a single File.renameTo of a whole directory. Measured on
 * 2026-10-01 against the Pixel: 32 MB across the Android/media boundary in 5 ms
 * and a nested tree in 78 ms, because within one volume this is a metadata
 * operation. That is what makes parking a multi-gigabyte media folder free, and
 * it is why there is no copy loop here to be interrupted halfway.
 */
final class Parking {

    public static final String PARKED_DIR_NAME = "WhatsApp";
    public static final String KEPT_DIR_NAME = "Databases-kept";
    static final String DATABASES = "Databases";

    private final File waFolder;
    private final File parkRoot;

    public Parking(File waFolder, File parkRoot) {
        this.waFolder = waFolder;
        this.parkRoot = parkRoot;
    }

    private File parkedFolder() {
        return new File(parkRoot, PARKED_DIR_NAME);
    }

    private File manifestFile() {
        return new File(parkRoot, ParkManifest.FILE_NAME);
    }

    public ParkManifest readManifest() {
        File f = manifestFile();
        if (!f.isFile()) {
            return null;
        }
        try {
            return ParkManifest.parse(readText(f));
        } catch (IOException e) {
            return null;
        }
    }

    public Result park(String waVersionName, long waVersionCode, String waSignatureSha256) {
        if (!hasChildren(waFolder)) {
            return Result.failed("Nothing to park: WhatsApp's folder is missing or empty.");
        }
        if (parkedFolder().isFile()) {
            return Result.failed("A file exists at the parked destination. Remove it first.");
        }

        // Before checking if a copy is parked, refuse if a manifest already exists in a non-RESTORED
        // state. A RESTORED manifest is allowed, so a second migration can start after the first
        // completes. It is set aside below, never overwritten. But a PARKING or PARKED
        // manifest must not be overwritten, or we destroy the hashes needed to verify a
        // put-back.
        ParkManifest existing = readManifest();
        // The one exception is a manifest with no parked folder behind it: nothing was moved,
        // so there are no hashes here to protect and refusing would be a dead end.
        if (existing != null && existing.state != ParkManifest.State.RESTORED
                && hasChildren(parkedFolder())) {
            return Result.failed("A migration in state " + existing.state + " already exists. "
                    + "Complete or reset it first.");
        }

        File manifestFile = manifestFile();

        File destination = parkedFolder();
        // Cleared before the manifest is written, so refusing here cannot leave behind a
        // PARKING manifest that nothing would ever clear.
        if (hasChildren(destination)) {
            return Result.failed("A copy is already parked. Put it back or delete it first.");
        }
        if (destination.isDirectory() && !destination.delete()) {
            return Result.failed("Could not clear the empty " + destination.getAbsolutePath());
        }

        ParkManifest manifest;
        try {
            List<ParkManifest.Db> dbs = Verify.describeDatabases(new File(waFolder, DATABASES));
            Verify.Tally tally = Verify.tally(waFolder);
            manifest = new ParkManifest(ParkManifest.State.PARKING, System.currentTimeMillis(),
                    waVersionName, waVersionCode, waSignatureSha256, Verify.cryptOf(dbs),
                    tally.files, tally.bytes, dbs);
        } catch (IOException e) {
            return Result.failed("Could not read WhatsApp's folder: " + e.getMessage());
        } catch (RuntimeException e) {
            return Result.failed("Could not describe WhatsApp's folder: " + e.getMessage());
        }

        if (!parkRoot.isDirectory() && !parkRoot.mkdirs()) {
            return Result.failed("Could not create " + parkRoot.getAbsolutePath());
        }

        // A RESTORED manifest, or one that cannot be read, belongs to an earlier move and
        // may be the only record of the databases kept beside it. Both are set aside, never
        // overwritten: until 2026-10-10 a park overwrote a RESTORED manifest here.
        if (manifestFile.isFile() && (existing == null || existing.state == ParkManifest.State.RESTORED)) {
            try {
                Leftovers.setAside(parkRoot, System.currentTimeMillis(), manifestFile,
                        new File(parkRoot, KEPT_DIR_NAME));
            } catch (IOException e) {
                return Result.failed("Could not set aside an earlier move's record: " + e.getMessage());
            }
        }

        // The manifest goes down before the move, written atomically to a temp file then renamed.
        // This way an interruption between the two is recognisable afterwards instead of looking
        // like a folder that appeared out of nowhere, and truncation is impossible — the manifest
        // is always either the old content or the new one, never half.
        try {
            atomicWriteText(manifestFile, manifest.render());
        } catch (IOException e) {
            return Result.failed("Could not write the manifest: " + e.getMessage());
        } catch (RuntimeException e) {
            return Result.failed("Could not render the manifest: " + e.getMessage());
        }

        if (!waFolder.renameTo(destination)) {
            // Only this call's PARKING manifest goes: whatever was here before is safe in previous-*.
            if (!manifestFile.delete()) {
                // A PARKING manifest with nothing parked is what the pre-check used to
                // refuse forever; name it so it can be removed by hand.
                return Result.failed("The move was refused by the filesystem, and the manifest"
                        + " could not be cleaned up.",
                        Arrays.asList("could not remove " + manifestFile.getAbsolutePath()));
            }
            return Result.failed("The move was refused by the filesystem. WhatsApp's folder is untouched.");
        }

        try {
            atomicWriteText(manifestFile(), manifest.withState(ParkManifest.State.PARKED).render());
        } catch (IOException e) {
            // The data is safe; only the bookkeeping failed. The folder is parked and put-back
            // will still work. Say so rather than claim failure over a completed move.
            return Result.failed("Moved, but the manifest could not be updated: " + e.getMessage(),
                    Arrays.asList("The folder is parked at " + destination.getAbsolutePath()));
        } catch (RuntimeException e) {
            return Result.failed("Moved, but the manifest could not be rendered: " + e.getMessage(),
                    Arrays.asList("The folder is parked at " + destination.getAbsolutePath()));
        }

        return Result.ok("Parked " + manifest.treeFiles + " files ("
                + megabytes(manifest.treeBytes) + " MB) at " + destination.getAbsolutePath());
    }

    static boolean hasChildren(File dir) {
        String[] names = dir.list();
        return names != null && names.length > 0;
    }

    static long megabytes(long bytes) {
        return bytes / (1024 * 1024);
    }

    static String readText(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try (InputStream in = new FileInputStream(f)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    static void writeText(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
        }
    }

    static void atomicWriteText(File f, String text) throws IOException {
        // Write to a temp file in the same directory and rename it atomically.
        // Within one directory, a rename is atomic on all platforms, so the manifest
        // file is always either the old content or the new one, never truncated.
        File temp = new File(f.getParentFile(), f.getName() + ".tmp");
        writeText(temp, text);
        if (!temp.renameTo(f)) {
            temp.delete();
            throw new IOException("Could not rename temp manifest to " + f.getAbsolutePath());
        }
    }

    static List<String> namesIn(File dir) {
        String[] names = dir.list();
        return names == null ? new ArrayList<String>() : Arrays.asList(names);
    }

    public Result putBack() {
        ParkManifest manifest = readManifest();
        File parked = parkedFolder();
        if (!hasChildren(parked) || manifest == null) {
            return Result.failed("Nothing is parked to put back.");
        }
        // Accept both PARKED and PARKING states when the park folder is present.
        // A crash between rename and flip leaves the data parked under a PARKING manifest.
        if (manifest.state != ParkManifest.State.PARKED && manifest.state != ParkManifest.State.PARKING) {
            return Result.failed("The manifest is in state " + manifest.state
                    + ", not ready to put back.");
        }
        if (hasChildren(waFolder)) {
            return Result.failed("Something is already in WhatsApp's folder and is in the way."
                    + " Nothing was moved.", namesIn(waFolder));
        }
        // Put-back renames first and flips afterwards, so an interruption must read as
        // PARKED + no parked folder. Left as PARKING it is byte-identical to an interrupted
        // park and MigrationState would call it a fresh start.
        if (manifest.state == ParkManifest.State.PARKING) {
            try {
                atomicWriteText(manifestFile(), manifest.withState(ParkManifest.State.PARKED).render());
            } catch (IOException e) {
                return Result.failed("Could not update the manifest before moving: " + e.getMessage());
            }
            manifest = manifest.withState(ParkManifest.State.PARKED);
        }

        // Uninstalling WhatsApp removes all of Android/media/com.whatsapp, so the
        // parent is usually gone by now. Assuming it exists is the ENOENT that
        // broke a real restore on 2026-09-30.
        File parent = waFolder.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return Result.failed("Could not create " + parent.getAbsolutePath());
        }
        if (waFolder.isDirectory() && !waFolder.delete()) {
            return Result.failed("Could not clear the empty " + waFolder.getAbsolutePath());
        }

        // Keep the databases -- megabytes, not gigabytes -- so that clearing
        // WhatsApp's storage later cannot delete the last copy, which is precisely
        // how a real recovery was lost on 2026-09-30. Verify them before the rename
        // so nothing has moved yet if we refuse.
        List<String> kept;
        try {
            kept = keepDatabases(new File(parked, DATABASES), manifest);
        } catch (IOException e) {
            return Result.failed("Could not keep a copy of the databases: " + e.getMessage(),
                    Arrays.asList(e.getMessage()));
        }

        if (!parked.renameTo(waFolder)) {
            return Result.failed("The parked data is untouched. The move back was refused"
                    + " by the filesystem.");
        }

        List<String> problems = Verify.compare(manifest, new File(waFolder, DATABASES));
        List<String> notes = new ArrayList<>(problems);
        String treeNote = Verify.compareTree(manifest, waFolder);
        if (treeNote != null) {
            notes.add(treeNote);
        }
        // Write the manifest only if verification succeeds. If hashes mismatch, leave
        // the manifest at PARKED/PARKING so the state machine reads it as "data back,
        // flip not done" and the user can retry.
        if (!problems.isEmpty()) {
            return Result.failed("Put back, but it does not match what was parked."
                    + " Check before registering.", notes);
        }

        try {
            atomicWriteText(manifestFile(), manifest.withState(ParkManifest.State.RESTORED).render());
        } catch (IOException e) {
            problems.add("The manifest could not be updated: " + e.getMessage());
            return Result.failed("Put back, but the manifest could not be marked restored: "
                    + e.getMessage(), notes);
        }

        Verify.Tally tally = Verify.tally(waFolder);
        return new Result(true, "Put back " + tally.files + " files, databases verified."
                + (kept.isEmpty() ? "" : " Kept a spare copy of " + kept.size() + "."), notes);
    }

    private List<String> keepDatabases(File parkedDatabases, ParkManifest manifest) throws IOException {
        List<String> kept = new ArrayList<>();
        File[] children = parkedDatabases.listFiles();
        if (children == null) {
            return kept;
        }
        File keptDir = new File(parkRoot, KEPT_DIR_NAME);
        if (hasChildren(keptDir)) {
            // Already holding exactly these databases: a put-back that stopped after making
            // its kept copy. Anything else here is an earlier move's, and may be somebody's
            // only copy of their chats, so it is set aside rather than cleared - which is
            // what happened here until 2026-10-10.
            if (Verify.compare(manifest, keptDir).isEmpty()) {
                for (File c : children) {
                    if (c.isFile()) {
                        kept.add(c.getName());
                    }
                }
                return kept;
            }
            Leftovers.setAside(parkRoot, System.currentTimeMillis(), keptDir);
        }
        if (!keptDir.isDirectory() && !keptDir.mkdirs()) {
            throw new IOException("could not create " + keptDir.getAbsolutePath());
        }
        for (File c : children) {
            if (!c.isFile()) {
                continue;
            }
            // The backup's own time, as the saved copy keeps it: the kept copy can be put
            // back (Saving.putBack), and one timed "now" would pass for a newer backup.
            Saving.copyFile(c, new File(keptDir, c.getName()), c.lastModified());
            kept.add(c.getName());
        }
        // Verify the spare copy against the manifest's recorded SHA-256s before the rename.
        // If they do not match, refuse the put-back with that reason. Doing it before the
        // rename means nothing has moved yet when we refuse.
        List<String> keptProblems = Verify.compare(manifest, keptDir);
        if (!keptProblems.isEmpty()) {
            throw new IOException("The spare databases do not match: " + keptProblems.toString());
        }
        return kept;
    }

    static boolean deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                if (!deleteTree(c)) {
                    // Stop at the first failure so a partial deletion is recognisable.
                    return false;
                }
            }
        }
        return f.delete();
    }
}
