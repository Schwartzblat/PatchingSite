package com.patcher.migrate;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The whole public surface, and the only place that reads the device.
 *
 * Everything with a decision in it lives behind this: MigrationState decides,
 * Parking moves, Verify checks, and none of them know what a Context is. That is
 * what lets this library be lifted into the app store app as a single button
 * without any of the logic coming along as UI code.
 *
 * status() is deliberately cheap -- presence only, never a walk -- so a screen
 * can call it on every resume.
 */
public final class Migration {

    private final Context context;
    private final Parking parking;
    private final Saving saving;
    private final Finishing finishing;
    private static final AtomicBoolean running = new AtomicBoolean(false);

    public Migration(Context context) {
        this.context = context.getApplicationContext();
        this.parking = new Parking(Paths.waFolder(), Paths.parkRoot());
        // Free space on shared storage, where both the copy and WhatsApp's folder live.
        this.saving = new Saving(Paths.waFolder(), Paths.parkRoot(),
                () -> Environment.getExternalStorageDirectory().getUsableSpace());
        this.finishing = new Finishing(Paths.waFolder(), Paths.parkRoot());
    }

    public Status status() {
        return MigrationState.of(facts());
    }

    private Facts facts() {
        PackageInfo info = null;
        boolean whatsAppInstalled = false;
        boolean whatsAppDataKept = false;
        String whatsAppSignature = null;
        String whatsAppVersionName = null;
        long whatsAppVersionCode = 0L;
        long whatsAppFirstInstallMs = 0L;

        try {
            info = whatsApp();
            if (info != null) {
                whatsAppInstalled = true;
                // Found only through MATCH_UNINSTALLED_PACKAGES: the app is gone but its
                // data was kept, and it still answers with the original's signature.
                whatsAppDataKept = info.applicationInfo != null
                        && (info.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) == 0;
                whatsAppVersionName = info.versionName;
                whatsAppVersionCode = info.getLongVersionCode();
                whatsAppSignature = signatureOf(info);
                // Survives an update and changes on a reinstall: how a saved copy tells the
                // WhatsApp it came from apart from one removed and installed again, empty.
                whatsAppFirstInstallMs = info.firstInstallTime;
            }
        } catch (RuntimeException e) {
            // PackageManager lookup failed (not NameNotFoundException, which is caught
            // inside whatsApp()). We cannot tell if WhatsApp is installed or not.
            // Treat it as installed with unreadable signature so the fail-safe in
            // installedIsTheOriginal() refuses the put-back rather than risk data loss.
            whatsAppInstalled = true;
            whatsAppSignature = null;
        }

        boolean hasAllFilesAccess;
        try {
            hasAllFilesAccess = Environment.isExternalStorageManager();
        } catch (RuntimeException e) {
            hasAllFilesAccess = false;
        }

        ParkManifest.Db newest = Verify.newestBackup(new File(Paths.waFolder(), Parking.DATABASES));
        File parkRoot = Paths.parkRoot();
        File kept = new File(parkRoot, Parking.KEPT_DIR_NAME);
        File saved = new File(parkRoot, Saving.SAVED);
        ParkManifest manifest = parking.readManifest();
        MoveRecord record = MoveRecord.readFrom(parkRoot);

        return new Facts(
                hasAllFilesAccess,
                whatsAppInstalled,
                whatsAppVersionName,
                whatsAppVersionCode,
                whatsAppSignature,
                Parking.hasChildren(Paths.waFolder()),
                Parking.hasChildren(new File(parkRoot, Parking.PARKED_DIR_NAME)),
                Parking.hasChildren(kept),
                manifest,
                kept.getAbsolutePath(),
                whatsAppDataKept,
                newest == null ? 0L : newest.mtimeMs,
                newest == null ? "none" : Verify.cryptOf(Collections.singletonList(newest)))
                // Sizes, not hashes: this runs on every resume.
                .withMove(
                        record,
                        record == null && MoveRecord.fileIn(parkRoot).isFile(),
                        Parking.hasChildren(saved),
                        record != null && Verify.sizesMatch(record.saved, saved),
                        Parking.hasChildren(new File(parkRoot, Saving.SAVED_TMP)),
                        manifest != null && Verify.sizesMatch(manifest.databases, kept),
                        whatsAppFirstInstallMs,
                        newest == null ? 0L : newest.size,
                        newest == null ? null : newest.name);
    }

    /**
     * Move WhatsApp's folder to the parked location and record its signature.
     * The original location is left empty; nothing remains at the source.
     *
     * Blocking; must not be called on the main thread.
     */
    public Result park() {
        if (!running.compareAndSet(false, true)) {
            return Result.failed("Another operation is already running.");
        }
        try {
            Facts f = facts();
            Status s = MigrationState.of(f);
            if (!s.canPark) {
                return Result.failed("Cannot park right now.", s.blockers);
            }
            if (f.installedSignatureSha256 == null || f.installedSignatureSha256.isEmpty()) {
                return Result.failed("WhatsApp is installed but its signing certificate could not"
                        + " be read, so a parked copy could not later be checked against it.");
            }
            return parking.park(f.installedVersionName, f.installedVersionCode,
                    f.installedSignatureSha256);
        } finally {
            running.set(false);
        }
    }

    /**
     * Copy the backup the store's guide accepted into WAMigrate/Saved, and write the
     * move's record. The store calls this on every open while the move is at its
     * start (MoveRepository.track), so it answers "nothing to do" far more often
     * than it copies.
     *
     * Blocking; must not be called on the main thread.
     */
    public Result saveBackup() {
        if (!running.compareAndSet(false, true)) {
            return Result.failed("Another operation is already running.");
        }
        try {
            Facts f = facts();
            Status s = MigrationState.of(f);
            if (s.step != Step.BACK_UP_AND_PARK || !f.hasAllFilesAccess || !f.whatsAppInstalled
                    || f.whatsAppDataKept || f.installedSignatureSha256 == null
                    || f.installedSignatureSha256.isEmpty()) {
                return Result.failed("There is no backup to save right now.", s.blockers);
            }
            long now = System.currentTimeMillis();
            // A manifest.txt that cannot be read, with nothing parked behind it, belongs to
            // no move the engine can see. It is set aside, with any kept databases beside
            // it, before a new record is written next to it.
            File parkRoot = Paths.parkRoot();
            File manifestFile = new File(parkRoot, ParkManifest.FILE_NAME);
            if (f.manifest == null && manifestFile.isFile()) {
                try {
                    Leftovers.setAside(parkRoot, now, manifestFile,
                            new File(parkRoot, Parking.KEPT_DIR_NAME));
                } catch (IOException e) {
                    return Result.failed("An earlier move's files could not be set aside: "
                            + e.getMessage());
                }
            }
            return saving.save(f.installedVersionName, f.installedVersionCode,
                    f.installedSignatureSha256, f.installedFirstInstallMs, now);
        } finally {
            running.set(false);
        }
    }

    /**
     * Put the backup back: the parked folder, by rename, when one is parked;
     * otherwise a copy (Databases-kept, or the saved copy). The record of the
     * put-back (when and from where, which the proof is measured from) is written
     * BEFORE anything moves. A put-back that then fails leaves restored_at set with
     * nothing back, which reads as PUT_BACK again, a retry. Written after, a failed
     * write could leave the previous round's confirmation and proof in place, read as
     * DONE, and let finish() delete the copies. withRestored clears both, so writing
     * first is what makes a half-finished put-back safe.
     *
     * Blocking; must not be called on the main thread.
     */
    public Result putBack() {
        if (!running.compareAndSet(false, true)) {
            return Result.failed("Another operation is already running.");
        }
        try {
            Facts f = facts();
            Status s = MigrationState.of(f);
            if (!s.canPutBack) {
                return Result.failed("Cannot put the backup back right now.", s.blockers);
            }
            boolean parked = f.parkFolderPresent && f.manifest != null
                    && f.manifest.state != ParkManifest.State.RESTORED;
            MoveRecord.Source from = parked ? MoveRecord.Source.PARKED : copyToPutBack(f);
            MoveRecord base = f.record != null ? f.record : MoveRecord.fromManifest(f.manifest, 0L);
            try {
                MoveRecord.writeTo(Paths.parkRoot(), base.withRestored(System.currentTimeMillis(), from));
            } catch (IOException e) {
                return Result.failed("The move's record could not be updated, so nothing was put back: "
                        + e.getMessage());
            }
            return parked ? parking.putBack() : saving.putBack(from, f.manifest, f.record);
        } finally {
            running.set(false);
        }
    }

    /**
     * The newer of the two copies, by the backup's own recorded time. The kept one on
     * a tie: it holds every database the park took, where the saved copy holds the
     * newest only.
     */
    private static MoveRecord.Source copyToPutBack(Facts f) {
        boolean kept = f.keptDatabasesPresent && f.keptMatches && f.manifest != null;
        boolean saved = f.savedPresent && f.savedMatches && f.record != null && f.record.hasSaved();
        if (kept && saved) {
            ParkManifest.Db k = Verify.newestCrypt(f.manifest.databases);
            ParkManifest.Db s = f.record.savedBackup();
            return s != null && (k == null || s.mtimeMs > k.mtimeMs)
                    ? MoveRecord.Source.SAVED : MoveRecord.Source.KEPT;
        }
        return saved ? MoveRecord.Source.SAVED : MoveRecord.Source.KEPT;
    }

    /**
     * Record the new WhatsApp's own backup as the move's proof, once one qualifies
     * (Finishing.findEvidence). The store calls this on every open while the move is
     * at VERIFY_AND_RESTORE, so it is a no-op far more often than not.
     *
     * Blocking; must not be called on the main thread.
     */
    public Result recordEvidence() {
        if (!running.compareAndSet(false, true)) {
            return Result.failed("Another operation is already running.");
        }
        try {
            Facts f = facts();
            Status s = MigrationState.of(f);
            if (s.step != Step.VERIFY_AND_RESTORE) {
                return Result.ok("Nothing to check right now.");
            }
            MoveRecord base = recordFor(f);
            if (base.evidenceAtMs > 0) {
                return Result.ok("Already proven.");
            }
            ParkManifest.Db putBack = f.putBackBackup();
            if (putBack == null) {
                return Result.ok("Nothing was put back that needs proof.");
            }
            ParkManifest.Db proof = finishing.findEvidence(base, f.manifest, putBack, base.restoredAtMs);
            if (proof == null) {
                return Result.ok("No backup from the new WhatsApp yet.");
            }
            MoveRecord.writeTo(Paths.parkRoot(), base.withEvidence(proof.mtimeMs, proof.size, proof.sha256));
            return Result.ok("Recorded the new WhatsApp's backup as proof.");
        } catch (IOException e) {
            return Result.failed("The new backup could not be checked: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /**
     * "My chats are back", kept in the record so it outlives the store's process.
     *
     * Blocking; must not be called on the main thread.
     */
    public Result confirmChats() {
        if (!running.compareAndSet(false, true)) {
            return Result.failed("Another operation is already running.");
        }
        try {
            Facts f = facts();
            Status s = MigrationState.of(f);
            if (s.step != Step.VERIFY_AND_RESTORE && s.step != Step.DONE) {
                return Result.failed("There is nothing to confirm right now.", s.blockers);
            }
            MoveRecord base = recordFor(f);
            if (base.chatsConfirmedAtMs > 0) {
                return Result.ok("Already confirmed.");
            }
            MoveRecord.writeTo(Paths.parkRoot(), base.withChatsConfirmed(System.currentTimeMillis()));
            return Result.ok("Noted that the chats are back.");
        } catch (IOException e) {
            return Result.failed("The confirmation could not be saved: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /**
     * Cancel a move at its start: deletes its saved copy and record, only while
     * WhatsApp still holds the same backup (Saving.cancel checks the hash).
     *
     * Blocking; must not be called on the main thread.
     */
    public Result cancel() {
        if (!running.compareAndSet(false, true)) {
            return Result.failed("Another operation is already running.");
        }
        try {
            Facts f = facts();
            Status s = MigrationState.of(f);
            if (!s.canCancel) {
                return Result.failed("The move can't be cancelled right now.", s.blockers);
            }
            return saving.cancel(f.record);
        } finally {
            running.set(false);
        }
    }

    /**
     * The one delete: all of WAMigrate, earlier moves' copies included. Only in DONE,
     * which needs the user's word and the new WhatsApp's own backup.
     *
     * Blocking; must not be called on the main thread.
     */
    public Result finish() {
        if (!running.compareAndSet(false, true)) {
            return Result.failed("Another operation is already running.");
        }
        try {
            Status s = status();
            if (!s.canFinish) {
                return Result.failed("There is nothing to remove yet.", s.blockers);
            }
            return finishing.finish();
        } finally {
            running.set(false);
        }
    }

    /** What finish() would remove, for the guide to list first. A walk: call it on that step only. */
    public List<SpareCopy> spareCopies() {
        return finishing.spareCopies();
    }

    /** How many earlier moves' copies are set aside (previous-*): one listing, no walk, so safe for the support email. */
    public int earlierCopyCount() {
        return Leftovers.previousCopies(Paths.parkRoot()).size();
    }

    /**
     * The move's record, made from the manifest for a move begun before records
     * existed. Its put-back time is then the manifest's own: a put-back rewrites the
     * manifest once, as RESTORED, and nothing writes it after.
     */
    private MoveRecord recordFor(Facts f) {
        if (f.record != null) {
            return f.record;
        }
        MoveRecord made = MoveRecord.fromManifest(f.manifest, 0L);
        if (f.manifest.state != ParkManifest.State.RESTORED) {
            return made;
        }
        long restoredAt = new File(Paths.parkRoot(), ParkManifest.FILE_NAME).lastModified();
        return made.withRestored(restoredAt, MoveRecord.Source.PARKED);
    }

    /**
     * The grant is a Settings toggle the user must flip; nothing in this app can grant
     * it. Start this intent to direct the user to the permission screen.
     */
    public static Intent allFilesAccessIntent(Context context) {
        return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
    }

    /**
     * Whether a patched build is compatible with a parked backup.
     *
     * WhatsApp refuses a backup made by a newer version of itself, and it does so
     * after the number has been verified with nothing left to go back to. A parked
     * version of 0 is permitted by the comparison itself — version codes are
     * non-negative, so it always satisfies patched >= parked.
     */
    public static boolean checkCompatible(long parkedVersionCode, long patchedVersionCode) {
        return patchedVersionCode >= parkedVersionCode;
    }

    /**
     * Check whether a patched build is compatible with the parked backup, reading
     * the parked version from the manifest itself. Spares the host from having to
     * dig facts out of Status.
     *
     * Returns false if a park folder is present but the manifest is unreadable,
     * since we cannot know whether the build is compatible. Returns true if there
     * is simply nothing parked.
     */
    public boolean checkCompatible(long patchedVersionCode) {
        Status s = status();
        if (s.facts.manifest == null) {
            // If there is a park folder but no readable manifest, we cannot know.
            if (s.facts.parkFolderPresent) {
                return false;
            }
            // Otherwise, nothing is parked, so the patched build is vacuously compatible.
            return true;
        }
        return checkCompatible(s.facts.manifest.waVersionCode, patchedVersionCode);
    }

    /**
     * MATCH_UNINSTALLED_PACKAGES so that a WhatsApp removed with "Keep app data"
     * ticked is still found. Its kept data blocks the differently-signed install, so
     * it must keep counting as the original -- see Facts.whatsAppDataKept.
     */
    private PackageInfo whatsApp() {
        try {
            return context.getPackageManager().getPackageInfo(Paths.WHATSAPP_PACKAGE,
                    PackageManager.GET_SIGNING_CERTIFICATES
                            | PackageManager.MATCH_UNINSTALLED_PACKAGES);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * The certificate digest, not the keystore's fingerprint from a build config:
     * recording whatever is installed at park time means the companion can tell
     * "the build I started from" from "some other build" without being told which
     * keystore to expect.
     *
     * Only signers[0] is hashed; hasMultipleSigners() is not consulted. This is
     * fine for WhatsApp but should be stated rather than implied.
     */
    private static String signatureOf(PackageInfo info) {
        if (info.signingInfo == null) {
            return null;
        }
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length == 0) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest(signers[0].toByteArray())) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
