package com.patcher.migrate;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The end of a move: the proof that it worked, and the one delete the engine has.
 *
 * The proof is the new WhatsApp's own backup. Anyone can tap "My chats are back"
 * on a WhatsApp that restored nothing. A backup it wrote after the put-back,
 * about as big as the one we put there and not a copy of it, is what a WhatsApp
 * that really restored the chats leaves behind, and an empty one cannot.
 */
final class Finishing {

    private final File waFolder;
    private final File parkRoot;

    Finishing(File waFolder, File parkRoot) {
        this.waFolder = waFolder;
        this.parkRoot = parkRoot;
    }

    /**
     * The new WhatsApp's backup that proves the move, or null when there is none yet:
     * the newest in its folder, written after [restoredAtMs], at least 3/4 the size of
     * [putBack], and with a SHA-256 unlike every file we put back. Our own file under
     * a new date never counts, and neither does a backup timed at the put-back itself.
     */
    ParkManifest.Db findEvidence(MoveRecord record, ParkManifest manifest, ParkManifest.Db putBack,
                                 long restoredAtMs) throws IOException {
        File dbDir = new File(waFolder, Parking.DATABASES);
        ParkManifest.Db candidate = Verify.newestBackup(dbDir);
        if (candidate == null || candidate.mtimeMs <= restoredAtMs) {
            return null;
        }
        if (candidate.size * 4 < putBack.size * 3) {
            return null;
        }
        String sha = Verify.sha256(new File(dbDir, candidate.name));
        if (manifest != null) {
            for (ParkManifest.Db d : manifest.databases) {
                if (sha.equals(d.sha256)) {
                    return null;
                }
            }
        }
        if (record != null) {
            for (ParkManifest.Db d : record.saved) {
                if (sha.equals(d.sha256)) {
                    return null;
                }
            }
        }
        return new ParkManifest.Db(candidate.name, candidate.size, candidate.mtimeMs, sha);
    }

    /**
     * Removes everything in WAMigrate: this move's copies and every earlier one set
     * aside. The caller has checked the move is DONE. The manifest and the record go
     * last, so a delete that stops part-way leaves them describing what is left - the
     * same ordering reason the old deleteParkedCopy had.
     */
    Result finish() {
        if (!parkRoot.isDirectory()) {
            return Result.ok("There was nothing left to remove.");
        }
        Verify.Tally t = Verify.tally(parkRoot);
        File manifest = new File(parkRoot, ParkManifest.FILE_NAME);
        File record = MoveRecord.fileIn(parkRoot);
        File[] children = parkRoot.listFiles();
        if (children != null) {
            Arrays.sort(children);
            for (File c : children) {
                if (c.equals(manifest) || c.equals(record)) {
                    continue;
                }
                if (!Parking.deleteTree(c)) {
                    return Result.failed("Could not delete " + c.getAbsolutePath());
                }
            }
        }
        if (manifest.exists() && !manifest.delete()) {
            return Result.failed("Could not delete " + manifest.getAbsolutePath());
        }
        if (record.exists() && !record.delete()) {
            return Result.failed("Could not delete " + record.getAbsolutePath());
        }
        parkRoot.delete();
        return Result.ok("Removed the spare copies: " + t.files + " files, "
                + Parking.megabytes(t.bytes) + " MB.");
    }

    /** Each folder finish() would remove. A walk, so only the guide's last step asks. */
    List<SpareCopy> spareCopies() {
        List<SpareCopy> out = new ArrayList<>();
        File[] children = parkRoot.listFiles();
        if (children == null) {
            return out;
        }
        Arrays.sort(children);
        for (File c : children) {
            if (c.isDirectory()) {
                Verify.Tally t = Verify.tally(c);
                out.add(new SpareCopy(c.getName(), c.lastModified(), t.files, t.bytes));
            }
        }
        return out;
    }
}
