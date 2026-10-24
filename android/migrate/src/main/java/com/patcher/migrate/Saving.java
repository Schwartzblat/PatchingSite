package com.patcher.migrate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The copy of the chat backup that protects a move before step 4 parks it.
 *
 * A copy, not a rename like Parking: until step 4 the backup has to stay in
 * WhatsApp's folder, where WhatsApp and step 4 expect it. That folder is exactly
 * what removing WhatsApp from Settings deletes, so the copy lives in WAMigrate.
 * Only the newest backup database and the Backups folder are copied - megabytes,
 * where the media can run to gigabytes - so a WhatsApp removed by hand loses its
 * photos and videos but not its chats. Step 4's park still keeps everything.
 *
 * Saved/ mirrors WhatsApp/'s layout (Databases/, Backups/), so a saved file's
 * recorded path is also where it goes back.
 *
 * Folders arrive as arguments, as for Parking, so this runs on a JVM against temp
 * folders. So does free space, so "no room" is testable.
 */
final class Saving {

    static final String SAVED = "Saved";
    static final String SAVED_TMP = "Saved.tmp";
    static final String SAVED_OLD = "Saved.old";
    static final String BACKUPS = "Backups";
    /** Built beside WhatsApp's folder and renamed onto it once verified; see putBack. */
    static final String RESTORING = "WhatsApp.restoring";
    /** Room left free on top of the copy itself, so saving a backup never fills the phone. */
    static final long MARGIN_BYTES = 50L * 1024 * 1024;

    private final File waFolder;
    private final File parkRoot;
    private final LongSupplier freeBytes;

    Saving(File waFolder, File parkRoot, LongSupplier freeBytes) {
        this.waFolder = waFolder;
        this.parkRoot = parkRoot;
        this.freeBytes = freeBytes;
    }

    private File folder(String name) {
        return new File(parkRoot, name);
    }

    /**
     * Saves the newest backup in WhatsApp's folder, or replaces the saved copy with
     * it. The caller (Migration) has already checked that the move is at its start
     * with the original installed; [signature] and [firstInstallMs] are that
     * original's.
     *
     * "Nothing to do" answers are ok: the store calls this on every open, and only a
     * copy that should have been made and was not is a failure.
     */
    Result save(String versionName, long versionCode, String signature, long firstInstallMs, long nowMs) {
        try {
            finishSwitch(nowMs);
        } catch (IOException e) {
            return Result.failed("An earlier copy could not be put in order: " + e.getMessage(),
                    Collections.singletonList(e.getMessage()));
        }
        ParkManifest.Db newest = Verify.newestBackup(new File(waFolder, Parking.DATABASES));
        if (newest == null) {
            return Result.failed("There is no backup in WhatsApp's folder to save.");
        }
        List<String> paths = sourcePaths(newest);
        MoveRecord record = MoveRecord.readFrom(parkRoot);
        // Checked before anything is looked at or set aside: a damaged copy is still the
        // copy of the original install's chats, and a reinstalled WhatsApp must never
        // become it. So Saved/ is left exactly as it is when this refuses.
        if (record != null && record.hasSaved()) {
            String identity = identityRefusal(record, signature, firstInstallMs);
            if (identity != null) {
                return Result.ok("Kept the saved copy: " + identity + ".");
            }
        }
        File saved = folder(SAVED);
        boolean savedOk = record != null && record.hasSaved() && Verify.sizesMatch(record.saved, saved);
        if (savedOk) {
            ParkManifest.Db current = record.savedBackup();
            if (current != null && current.name.equals(Parking.DATABASES + "/" + newest.name)
                    && current.size == newest.size && current.mtimeMs == newest.mtimeMs) {
                return Result.ok("The newest backup is already saved.");
            }
            String refusal = contentRefusal(current, newest);
            if (refusal != null) {
                return Result.ok("Kept the saved copy: " + refusal + ".");
            }
        } else if (Parking.hasChildren(saved)) {
            try {
                // A Saved/ that no record describes. Killed between its rename and the
                // record's write, it is a good copy of this very backup: keep it.
                if (record == null && sameAsSource(saved, paths)) {
                    return recordExisting(saved, paths, versionName, versionCode, signature,
                            firstInstallMs, newest, nowMs);
                }
                // Anything else in its place is set aside, never overwritten.
                Leftovers.setAside(parkRoot, nowMs, saved);
            } catch (IOException e) {
                return Result.failed("A copy already here could not be checked or set aside: "
                        + e.getMessage(), Collections.singletonList(e.getMessage()));
            }
        }

        long bytes = 0;
        for (String p : paths) {
            bytes += new File(waFolder, p).length();
        }
        long needed = bytes + MARGIN_BYTES;
        if (freeBytes.getAsLong() < needed) {
            return Result.noRoom(needed);
        }

        File tmp = folder(SAVED_TMP);
        List<ParkManifest.Db> copied;
        try {
            // Only ever our own unfinished copy, and the backup it was made from is still
            // in WhatsApp's folder (newest, above), so starting over loses nothing.
            if (tmp.exists() && !Parking.deleteTree(tmp)) {
                throw new IOException("could not clear " + tmp.getAbsolutePath());
            }
            copied = copyVerified(waFolder, tmp, paths);
        } catch (IOException e) {
            return Result.failed("Your backup couldn't be copied: " + e.getMessage(),
                    Collections.singletonList(e.getMessage()));
        }

        String crypt = Verify.cryptOf(Collections.singletonList(newest));
        MoveRecord next = record == null
                ? new MoveRecord(versionName, versionCode, signature, firstInstallMs, crypt, nowMs, copied,
                        0L, MoveRecord.Source.NONE, 0L, 0L, 0L, "")
                : record.withSaved(versionName, versionCode, crypt, nowMs, copied);
        try {
            if (savedOk) {
                // Replacing: the old copy stays until the record points at the new one, so
                // an interruption anywhere here leaves a copy the record describes
                // (finishSwitch, next time).
                File old = folder(SAVED_OLD);
                if (!saved.renameTo(old)) {
                    throw new IOException("could not move the earlier copy aside");
                }
                if (!tmp.renameTo(saved)) {
                    old.renameTo(saved);
                    throw new IOException("could not move the new copy into place");
                }
                MoveRecord.writeTo(parkRoot, next);
                if (!Parking.deleteTree(old)) {
                    return Result.ok("Saved a newer copy of your backup; the earlier one goes next time.");
                }
            } else {
                if (saved.isDirectory() && !saved.delete()) {
                    throw new IOException("could not clear the empty " + saved.getAbsolutePath());
                }
                if (!tmp.renameTo(saved)) {
                    throw new IOException("could not move the copy into place");
                }
                MoveRecord.writeTo(parkRoot, next);
            }
        } catch (IOException e) {
            return Result.failed("Your backup was copied but couldn't be recorded: " + e.getMessage(),
                    Collections.singletonList(e.getMessage()));
        }
        return Result.ok("Saved a copy of your backup: " + copied.size() + " files, "
                + Parking.megabytes(bytes) + " MB.");
    }

    /**
     * Puts a copy back into WhatsApp's empty folder: Saved/ (each file to the path it
     * was saved from) or Databases-kept/ (into Databases/). Copied, never moved, so
     * the copy outlives a WhatsApp that is removed again before the move is proven.
     * Built in WhatsApp.restoring beside the folder and renamed onto it only once
     * every file matches its record, so WhatsApp never finds half a backup.
     */
    Result putBack(MoveRecord.Source from, ParkManifest manifest, MoveRecord record) {
        List<ParkManifest.Db> expected = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        File source;
        if (from == MoveRecord.Source.SAVED && record != null && record.hasSaved()) {
            source = folder(SAVED);
            for (ParkManifest.Db d : record.saved) {
                expected.add(d);
                targets.add(d.name);
            }
        } else if (from == MoveRecord.Source.KEPT && manifest != null && !manifest.databases.isEmpty()) {
            source = folder(Parking.KEPT_DIR_NAME);
            for (ParkManifest.Db d : manifest.databases) {
                expected.add(d);
                targets.add(Parking.DATABASES + "/" + d.name);
            }
        } else {
            return Result.failed("There is no copy to put back.");
        }
        if (Parking.hasChildren(waFolder)) {
            return Result.failed("Something is already in WhatsApp's folder and is in the way."
                    + " Nothing was moved.", Parking.namesIn(waFolder));
        }
        List<String> problems = Verify.compareFiles(expected, source);
        if (!problems.isEmpty()) {
            return Result.failed("The copy doesn't match its record, so it wasn't put back.", problems);
        }
        File parent = waFolder.getParentFile();
        File staging = new File(parent, RESTORING);
        try {
            // Uninstalling WhatsApp removes all of Android/media/com.whatsapp; see Parking.putBack.
            if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("could not create " + parent.getAbsolutePath());
            }
            // Only ever our own half-built folder from an interrupted put-back; the copy is still whole.
            if (staging.exists() && !Parking.deleteTree(staging)) {
                throw new IOException("could not clear " + staging.getAbsolutePath());
            }
            for (int i = 0; i < expected.size(); i++) {
                ParkManifest.Db d = expected.get(i);
                File placed = new File(staging, targets.get(i));
                // The recorded time, not the copy's: a backup that looks newer than the put-back
                // would pass for the new WhatsApp's own (Finishing.findEvidence).
                copyFile(new File(source, d.name), placed, d.mtimeMs);
                if (!Verify.sha256(placed).equals(d.sha256)) {
                    throw new IOException(targets.get(i) + " did not match after copying");
                }
            }
            if (waFolder.isDirectory() && !waFolder.delete()) {
                throw new IOException("could not clear the empty " + waFolder.getAbsolutePath());
            }
            if (!staging.renameTo(waFolder)) {
                throw new IOException("the filesystem refused the last rename");
            }
        } catch (IOException e) {
            return Result.failed("Your backup couldn't be put back: " + e.getMessage(),
                    Collections.singletonList(e.getMessage()));
        }
        return Result.ok("Put back " + expected.size() + " files from the "
                + (from == MoveRecord.Source.SAVED ? "saved" : "kept") + " copy.");
    }

    /**
     * Why a WhatsApp removed and installed again, or a different certificate, must not
     * touch the saved copy; null when neither applies. WhatsApp writes a backup by
     * itself every night, and a reinstalled WhatsApp writes one with nothing in it.
     * This holds even when the copy is damaged: the rule protects the real chats, not
     * the copy's health, so it is checked before the copy is examined at all.
     */
    private static String identityRefusal(MoveRecord record, String signature, long firstInstallMs) {
        if (!record.waSignatureSha256.equalsIgnoreCase(signature)) {
            return "a different WhatsApp made the newer backup";
        }
        if (record.waFirstInstallMs != firstInstallMs) {
            return "WhatsApp was installed again since it was saved";
        }
        return null;
    }

    /**
     * Why [newest] must not replace an intact saved copy whose identity already matches;
     * null when it may. Compared only against an intact copy, because a damaged one
     * is set aside and made again rather than judged by its times and sizes.
     */
    private static String contentRefusal(ParkManifest.Db current, ParkManifest.Db newest) {
        if (current == null) {
            return null;
        }
        if (newest.mtimeMs <= current.mtimeMs) {
            return "the backup in WhatsApp isn't newer";
        }
        if (newest.size * 4 < current.size * 3) {
            return "the newer backup is much smaller than the saved one";
        }
        return null;
    }

    /** The newest backup database and every file in Backups/, by path inside WhatsApp's folder. */
    private List<String> sourcePaths(ParkManifest.Db newest) {
        List<String> paths = new ArrayList<>();
        paths.add(Parking.DATABASES + "/" + newest.name);
        File[] backups = new File(waFolder, BACKUPS).listFiles();
        if (backups != null) {
            Arrays.sort(backups);
            for (File f : backups) {
                if (f.isFile()) {
                    paths.add(BACKUPS + "/" + f.getName());
                }
            }
        }
        return paths;
    }

    private boolean sameAsSource(File copy, List<String> paths) throws IOException {
        for (String p : paths) {
            File source = new File(waFolder, p);
            File copied = new File(copy, p);
            if (!copied.isFile() || copied.length() != source.length()
                    || !Verify.sha256(copied).equals(Verify.sha256(source))) {
                return false;
            }
        }
        return true;
    }

    private Result recordExisting(File saved, List<String> paths, String versionName, long versionCode,
                                  String signature, long firstInstallMs, ParkManifest.Db newest,
                                  long nowMs) throws IOException {
        List<ParkManifest.Db> described = new ArrayList<>();
        for (String p : paths) {
            File source = new File(waFolder, p);
            described.add(new ParkManifest.Db(p, source.length(), source.lastModified(),
                    Verify.sha256(new File(saved, p))));
        }
        MoveRecord.writeTo(parkRoot, new MoveRecord(versionName, versionCode, signature, firstInstallMs,
                Verify.cryptOf(Collections.singletonList(newest)), nowMs, described, 0L,
                MoveRecord.Source.NONE, 0L, 0L, 0L, ""));
        return Result.ok("Recorded the copy that was already made.");
    }

    /**
     * A replacement interrupted between its renames leaves Saved.old beside Saved/.
     * It is finished from what the record says, which describes exactly one of the two.
     */
    private void finishSwitch(long nowMs) throws IOException {
        File old = folder(SAVED_OLD);
        if (!old.exists()) {
            return;
        }
        MoveRecord record = MoveRecord.readFrom(parkRoot);
        File saved = folder(SAVED);
        boolean described = record != null && record.hasSaved();
        if (described && Verify.compareFiles(record.saved, saved).isEmpty()) {
            // The record already points at the new copy: removing the old one was all that was left.
            if (!Parking.deleteTree(old)) {
                throw new IOException("could not remove " + old.getAbsolutePath());
            }
        } else if (described && Verify.compareFiles(record.saved, old).isEmpty()) {
            // The record still describes the old copy: undo the switch. The newer copy
            // becomes an unfinished one again, and is made again from WhatsApp's folder.
            if (saved.exists()) {
                Leftovers.setAside(parkRoot, nowMs, folder(SAVED_TMP));
                if (!saved.renameTo(folder(SAVED_TMP))) {
                    throw new IOException("could not move " + saved.getAbsolutePath() + " aside");
                }
            }
            if (!old.renameTo(saved)) {
                throw new IOException("could not move " + old.getAbsolutePath() + " back");
            }
        } else {
            Leftovers.setAside(parkRoot, nowMs, old);
        }
    }

    /**
     * Copies each of [paths] from [from] to [to], gives each copy its source's time,
     * and hashes both sides. Each is recorded with the source's time, never the
     * copy's: the record is compared with WhatsApp's own files, and a filesystem that
     * refuses to set a time must not make the same backup look newer on every open.
     */
    static List<ParkManifest.Db> copyVerified(File from, File to, List<String> paths) throws IOException {
        List<ParkManifest.Db> out = new ArrayList<>();
        for (String p : paths) {
            File source = new File(from, p);
            File copy = new File(to, p);
            copyFile(source, copy, source.lastModified());
            String want = Verify.sha256(source);
            String got = Verify.sha256(copy);
            if (!want.equals(got)) {
                throw new IOException(p + " did not match after copying");
            }
            out.add(new ParkManifest.Db(p, source.length(), source.lastModified(), got));
        }
        return out;
    }

    /**
     * One file, timed [mtimeMs]. setLastModified's answer is ignored on purpose: where
     * a filesystem refuses, a copy only looks newer than it is, and the proof check
     * (Finishing.findEvidence) refuses anything whose hash matches a file we put back.
     */
    static void copyFile(File source, File copy, long mtimeMs) throws IOException {
        File parent = copy.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent.getAbsolutePath());
        }
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(copy)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        }
        copy.setLastModified(mtimeMs);
    }

    /**
     * Cancel, at the start of a move: deletes this move's copy and its record, and
     * nothing else (an earlier move's previous-* folders stay). Deleted only while
     * WhatsApp's own folder still holds the saved backup - the same SHA-256 - or a
     * newer one nearly as big. That is checked here, by hash, just before the delete,
     * because the copy must never be the only one. Otherwise the copy and its record
     * are set aside instead (since 2026-10-24, when cancel became possible at every
     * step), so the user can still start over.
     */
    Result cancel(MoveRecord record, long nowMs) {
        ParkManifest.Db saved = record != null ? record.savedBackup() : null;
        if (saved != null && !stillInWhatsApp(saved)) {
            try {
                Leftovers.setAside(parkRoot, nowMs, folder(SAVED_TMP), folder(SAVED_OLD), folder(SAVED),
                        MoveRecord.fileIn(parkRoot));
            } catch (IOException e) {
                return Result.failed("The move couldn't be cancelled: " + e.getMessage(),
                        Collections.singletonList(e.getMessage()));
            }
            return Result.ok("Cancelled the move. WhatsApp no longer holds the saved backup, so the"
                    + " copy was set aside.");
        }
        for (String name : new String[]{SAVED_TMP, SAVED_OLD, SAVED}) {
            File f = folder(name);
            if (f.exists() && !Parking.deleteTree(f)) {
                return Result.failed("Could not delete " + f.getAbsolutePath());
            }
        }
        File recordFile = MoveRecord.fileIn(parkRoot);
        if (recordFile.exists() && !recordFile.delete()) {
            return Result.failed("Could not delete " + recordFile.getAbsolutePath());
        }
        // Succeeds only when nothing else is left in it.
        parkRoot.delete();
        return Result.ok("Cancelled the move and removed the saved copy.");
    }

    private boolean stillInWhatsApp(ParkManifest.Db saved) {
        File same = new File(waFolder, saved.name);
        try {
            if (same.isFile() && same.length() == saved.size && Verify.sha256(same).equals(saved.sha256)) {
                return true;
            }
        } catch (IOException e) {
            // Unreadable counts as not there.
        }
        ParkManifest.Db newest = Verify.newestBackup(new File(waFolder, Parking.DATABASES));
        return newest != null && newest.mtimeMs > saved.mtimeMs && newest.size * 4 >= saved.size * 3;
    }
}
