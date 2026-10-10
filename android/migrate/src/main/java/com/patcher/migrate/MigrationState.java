package com.patcher.migrate;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Facts in, Status out, nothing else. Pure so that every combination below is a
 * unit test rather than a thing discovered on somebody's phone with their only
 * copy of their chat history in play.
 *
 * The grant is not a step. It can be missing at any point in the procedure --
 * revoked from Settings while the app sits in the background -- so it is a
 * blocker on every action and leaves the step where it was.
 *
 * The rows follow the 2026-10-10 spec's states table, in its order: the first
 * that matches wins. No row allows a delete before DONE.
 */
final class MigrationState {

    private MigrationState() {
    }

    public static Status of(Facts f) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!f.hasAllFilesAccess) {
            blockers.add("All files access is not granted, so this app cannot touch"
                    + " WhatsApp's folder.");
        }

        // Emit crypt15 warning early so it appears at UNINSTALL_WHATSAPP and PUT_BACK,
        // before the user uninstalls. Arriving only at VERIFY_AND_RESTORE is too late.
        // Before anything is parked there is no manifest yet, so the saved copy's
        // record answers, and before that the newest backup in WhatsApp's own folder
        // -- earliest of all, while the user can still go and find their key.
        String crypt = f.manifest != null ? f.manifest.crypt
                : f.record != null ? f.record.crypt : f.newestBackupCrypt;
        if ("crypt15".equals(crypt)) {
            warnings.add("⚠  crypt15 means end-to-end encrypted backups are on."
                    + " WhatsApp's servers will not give the key back, so you will"
                    + " need your own 64-digit key or password to restore.");
        }

        // A record we cannot read may be the only description of the only copy.
        if (f.recordUnreadable) {
            blockers.add("The move's record (move.txt) is unreadable. Nothing will be moved or"
                    + " deleted automatically.");
            return attention(f, blockers, warnings);
        }

        // A parked folder we cannot describe is the one state where stopping beats
        // guessing: the manifest is what says which direction the data should move.
        if (f.parkFolderPresent && f.manifest == null) {
            blockers.add("A parked folder is here but its manifest is missing or unreadable."
                    + " Nothing will be moved automatically -- move it yourself, or delete it"
                    + " if you know it is not needed.");
            return attention(f, blockers, warnings);
        }

        boolean parked = f.parkFolderPresent && f.manifest != null
                && f.manifest.state != ParkManifest.State.RESTORED;
        boolean restored = (f.manifest != null && f.manifest.state == ParkManifest.State.RESTORED)
                || (f.record != null && f.record.restoredAtMs > 0);

        // Check for interrupted put-back: PARKED manifest but park folder is gone
        if (f.manifest != null && f.manifest.state == ParkManifest.State.PARKED && !f.parkFolderPresent) {
            if (f.waFolderPresent) {
                // Put-back rename completed, manifest flip didn't — treat as restored
                restored = true;
            } else {
                // Neither location has the folder — broken state
                String msg = "Neither the parked folder nor WhatsApp's folder is present,"
                        + " though a parked-state manifest exists. The migration state is broken"
                        + " and cannot proceed.";
                if (f.keptDatabasesPresent) {
                    msg += " Your databases may still be in the kept copy at "
                            + (f.keptDatabasesPath != null ? f.keptDatabasesPath
                                    : "Databases-kept in the WAMigrate folder")
                            + " -- do not delete it.";
                }
                blockers.add(msg);
                return attention(f, blockers, warnings);
            }
        }

        if (parked) {
            return parked(f, blockers, warnings);
        }
        if (restored) {
            return afterPutBack(f, blockers, warnings);
        }
        if (f.record != null) {
            return recorded(f, blockers, warnings);
        }
        // A copy no record describes, and no backup left in WhatsApp's folder to check
        // it against: killed between its rename and the record's write, then WhatsApp
        // was removed. It may be the only copy there is.
        if ((f.savedPresent || f.savedTmpPresent) && f.newestBackupMs <= 0) {
            blockers.add("A saved copy is here without its record, and WhatsApp's own backup is"
                    + " gone. Nothing will be moved or deleted automatically.");
            return attention(f, blockers, warnings);
        }
        return atStart(f, blockers, warnings, false);
    }

    /** Parked: as before 2026-10-10, minus any delete, plus the check against a saved copy. */
    private static Status parked(Facts f, List<String> blockers, List<String> warnings) {
        String behind = parkedBehindSaved(f);
        if (behind != null) {
            blockers.add(behind);
            return attention(f, blockers, warnings);
        }
        if (f.waFolderPresent) {
            blockers.add("A copy is already parked" + when(f) + ". Put it back or delete"
                    + " it before parking again.");
        }
        if (f.installedIsTheOriginal()) {
            if (f.installedSignatureSha256 == null || f.installedSignatureSha256.isEmpty()) {
                blockers.add("WhatsApp is installed but its signing certificate could not be"
                        + " read, so this cannot tell whether it is the build you parked from."
                        + " Put-back is refused rather than risk deleting your only copy.");
            } else {
                blockers.add("WhatsApp is still installed. Uninstalling it deletes"
                        + " Android/media/com.whatsapp, so the backup stays parked until then.");
            }
            return new Status(Step.UNINSTALL_WHATSAPP, !f.hasAllFilesAccess,
                    false, false, false, false, blockers, warnings, f);
        }
        if (f.waFolderPresent) {
            blockers.add("Something is already in WhatsApp's folder and is in the way."
                    + " Put-back refuses rather than merge two folders together.");
        }
        boolean can = f.hasAllFilesAccess && !f.waFolderPresent;
        return new Status(Step.PUT_BACK, !f.hasAllFilesAccess,
                false, can, false, false, blockers, warnings, f);
    }

    /**
     * The park moves the very folder the saved copy was made from, so its backup is
     * the saved one or newer. Older, or much smaller, means something else happened
     * in between (a reinstalled WhatsApp's empty backup), and neither copy is put
     * back without a person looking.
     */
    private static String parkedBehindSaved(Facts f) {
        ParkManifest.Db saved = f.record != null ? f.record.savedBackup() : null;
        if (saved == null) {
            return null;
        }
        ParkManifest.Db parked = Verify.newestCrypt(f.manifest.databases);
        if (parked != null && parked.mtimeMs >= saved.mtimeMs && parked.size * 4 >= saved.size * 3) {
            return null;
        }
        return "The parked backup is older or much smaller than the copy saved earlier in this"
                + " move, so neither is put back automatically.";
    }

    /** Put back at least once: the spec's rows 4a-4e. */
    private static Status afterPutBack(Facts f, List<String> blockers, List<String> warnings) {
        boolean access = f.hasAllFilesAccess;
        // Put back from the park, but the park's record can't be read: putBackBackup() would
        // read the null manifest as "no database put back", and the user's word alone
        // would reach DONE and finish(), deleting a move nothing could check.
        if (f.record != null && f.manifest == null
                && (f.record.restoredFrom == MoveRecord.Source.PARKED
                || f.record.restoredFrom == MoveRecord.Source.KEPT)) {
            blockers.add("The backup was put back from the parked copy, but the park's record"
                    + " (manifest.txt) is missing or unreadable, so this move can't be checked."
                    + " Nothing will be deleted automatically.");
            return attention(f, blockers, warnings);
        }
        // If WhatsApp is installed but signature is unreadable, we cannot identify it
        if (f.whatsAppInstalled && (f.installedSignatureSha256 == null || f.installedSignatureSha256.isEmpty())) {
            blockers.add("WhatsApp is installed but its signing certificate could not be"
                    + " read, so this cannot tell whether it is the patched build or the one"
                    + " you started with. Verification is refused rather than risk restoring"
                    + " to the wrong app.");
            return attention(f, blockers, warnings);
        }
        // WhatsApp's folder is empty again: the WhatsApp the backup went into was removed
        // before the move was proven, which deletes the folder a second time. Before
        // 2026-10-10 this read as "install it", and the install went ahead with no
        // backup while Databases-kept still held one.
        if (!f.waFolderPresent && !f.installedIsTheOriginal()) {
            if (f.copyAvailable()) {
                return new Status(Step.PUT_BACK, !access, false, access, false, false,
                        blockers, warnings, f);
            }
            blockers.add("The backup that was put back is gone from WhatsApp's folder, and no copy"
                    + " of it is left here.");
            return attention(f, blockers, warnings);
        }
        if (f.installedIsTheOriginal()) {
            blockers.add("The WhatsApp installed is the one you started with, not the"
                    + " patched build. Uninstall it and install the patched APK.");
            return new Status(Step.INSTALL_PATCHED, !access, false, false, false, false,
                    blockers, warnings, f);
        }
        if (!f.whatsAppInstalled) {
            return new Status(Step.INSTALL_PATCHED, !access, false, false, false, false,
                    blockers, warnings, f);
        }
        boolean done = proven(f);
        return new Status(done ? Step.DONE : Step.VERIFY_AND_RESTORE, !access, false, false,
                done && access, false, blockers, warnings, f);
    }

    /**
     * The user said the chats are back, and the new WhatsApp wrote a backup of its
     * own (Finishing.findEvidence). The word alone is not enough: the 2026-10-10 owner
     * wanted the spare copy kept until the move is proven. A put-back that held no
     * backup database has nothing to prove, so the word is all there can be.
     *
     * Both must come after the latest put-back (restored_at_ms): a confirmation or
     * proof from before it was about a WhatsApp that has gone, and counting it would
     * let finish() delete the copies before the new one has restored anything.
     */
    private static boolean proven(Facts f) {
        MoveRecord r = f.record;
        if (r == null || r.chatsConfirmedAtMs <= r.restoredAtMs) {
            return false;
        }
        return r.evidenceAtMs > r.restoredAtMs || f.putBackBackup() == null;
    }

    /** A record and nothing parked or put back: the spec's rows 5a-5e. */
    private static Status recorded(Facts f, List<String> blockers, List<String> warnings) {
        boolean access = f.hasAllFilesAccess;
        boolean original = f.installedIsTheOriginal();
        if (original && f.whatsAppDataKept) {
            blockers.add("WhatsApp was removed with its data kept, which stops the other WhatsApp"
                    + " installing. Your backup is saved.");
            return new Status(Step.UNINSTALL_WHATSAPP, !access, false, false, false, false,
                    blockers, warnings, f);
        }
        if (original) {
            return atStart(f, blockers, warnings, canCancel(f));
        }
        if (f.waFolderPresent) {
            blockers.add("Something is already in WhatsApp's folder and is in the way."
                    + " Put-back refuses rather than merge two folders together.");
            return new Status(Step.PUT_BACK, !access, false, false, false, false, blockers, warnings, f);
        }
        if (f.savedPresent && f.savedMatches) {
            return new Status(Step.PUT_BACK, !access, false, access, false, false, blockers, warnings, f);
        }
        blockers.add("WhatsApp was removed, and the saved copy of its backup is missing or doesn't"
                + " match its record.");
        return attention(f, blockers, warnings);
    }

    /**
     * Only while WhatsApp's own folder still holds the saved backup, or a newer one
     * nearly as big: cancelling deletes the saved copy, which must never be the only
     * one. By name, size and time here, since status() never hashes; Saving.cancel
     * checks the hash before it deletes anything.
     */
    private static boolean canCancel(Facts f) {
        if (!f.hasAllFilesAccess || f.whatsAppDataKept || f.originalReinstalled()
                || f.installedSignatureSha256 == null || f.installedSignatureSha256.isEmpty()) {
            return false;
        }
        ParkManifest.Db saved = f.record.savedBackup();
        if (saved == null) {
            return true;
        }
        boolean same = (Parking.DATABASES + "/" + f.newestBackupName).equals(saved.name)
                && f.newestBackupSize == saved.size && f.newestBackupMs == saved.mtimeMs;
        boolean newer = f.newestBackupMs > saved.mtimeMs && f.newestBackupSize * 4 >= saved.size * 3;
        return same || newer;
    }

    /** Nothing parked and nothing put back: the beginning. */
    private static Status atStart(Facts f, List<String> blockers, List<String> warnings,
                                  boolean canCancel) {
        if (!f.whatsAppInstalled) {
            return new Status(Step.NOTHING_TO_MIGRATE, !f.hasAllFilesAccess,
                    false, false, false, false, blockers, warnings, f);
        }
        if (f.installedSignatureSha256 == null || f.installedSignatureSha256.isEmpty()) {
            blockers.add("WhatsApp is installed but its signing certificate could not be"
                    + " read, so a parked copy could not later be checked against it."
                    + " Parking is refused.");
        }
        if (!f.waFolderPresent) {
            blockers.add("Nothing to park: WhatsApp's folder is missing or empty. Make a"
                    + " backup in WhatsApp first (Settings, Chats, Chat backup, Back up).");
        }
        boolean can = f.hasAllFilesAccess && f.waFolderPresent
                && (f.installedSignatureSha256 != null && !f.installedSignatureSha256.isEmpty());
        return new Status(Step.BACK_UP_AND_PARK, !f.hasAllFilesAccess,
                can, false, false, canCancel, blockers, warnings, f);
    }

    private static Status attention(Facts f, List<String> blockers, List<String> warnings) {
        return new Status(Step.NEEDS_ATTENTION, !f.hasAllFilesAccess,
                false, false, false, false, blockers, warnings, f);
    }

    private static String when(Facts f) {
        return " (" + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date(f.manifest.parkedAtMs)) + ")";
    }
}
