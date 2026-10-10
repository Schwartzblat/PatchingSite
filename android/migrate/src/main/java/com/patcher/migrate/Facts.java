package com.patcher.migrate;

/**
 * Everything observable that the decision depends on, gathered in one place so
 * the decision itself can be a pure function and therefore testable without a
 * device.
 *
 * waFolderPresent and parkFolderPresent mean "exists and has at least one
 * child". An empty directory is treated as absent throughout, because an empty
 * WhatsApp/ left behind by a launch of the patched app must not block a put-back.
 */
public final class Facts {

    public final boolean hasAllFilesAccess;
    public final boolean whatsAppInstalled;
    public final String installedVersionName;
    public final long installedVersionCode;
    public final String installedSignatureSha256;
    public final boolean waFolderPresent;
    public final boolean parkFolderPresent;
    public final boolean keptDatabasesPresent;
    /** null when there is no sidecar, or it could not be trusted. */
    public final ParkManifest manifest;
    /** Absolute path of the kept databases, for messages; may be null. */
    public final String keptDatabasesPath;
    /**
     * WhatsApp was uninstalled with "Keep app data" ticked. whatsAppInstalled stays
     * true in that case, with the original's signature, because the leftover data is
     * exactly what makes the differently-signed install fail: treating it as gone
     * would put the backup back in front of an install that cannot happen, where the
     * next uninstall deletes it.
     */
    public final boolean whatsAppDataKept;
    /**
     * mtime of the newest backup in WhatsApp's own Databases folder, 0 when there is
     * none. Read before anything is parked, so a host can say how old the backup is
     * while the user can still make a fresh one.
     */
    public final long newestBackupMs;
    /** crypt14, crypt15 or none, for that same newest backup. */
    public final String newestBackupCrypt;
    /** The move's record (move.txt); null when there is none, or it cannot be trusted. */
    public final MoveRecord record;
    /** move.txt is there but cannot be trusted: everything stops rather than guess. */
    public final boolean recordUnreadable;
    /** Saved/ holds files. */
    public final boolean savedPresent;
    /** Saved/ holds what the record lists, by size. status() never hashes; the put-back does. */
    public final boolean savedMatches;
    /** Saved.tmp/ holds files: a copy that never finished. */
    public final boolean savedTmpPresent;
    /** Databases-kept/ holds what the manifest lists, by size. */
    public final boolean keptMatches;
    /** The installed WhatsApp's PackageInfo.firstInstallTime; 0 when none is installed. */
    public final long installedFirstInstallMs;
    /** Size of the newest backup in WhatsApp's own folder (the one newestBackupMs times); 0 when none. */
    public final long newestBackupSize;
    /** Its file name, e.g. msgstore.db.crypt14; null when none. */
    public final String newestBackupName;

    public Facts(boolean hasAllFilesAccess, boolean whatsAppInstalled, String installedVersionName,
                 long installedVersionCode, String installedSignatureSha256,
                 boolean waFolderPresent, boolean parkFolderPresent, boolean keptDatabasesPresent,
                 ParkManifest manifest) {
        this(hasAllFilesAccess, whatsAppInstalled, installedVersionName, installedVersionCode,
                installedSignatureSha256, waFolderPresent, parkFolderPresent, keptDatabasesPresent,
                manifest, null);
    }

    public Facts(boolean hasAllFilesAccess, boolean whatsAppInstalled, String installedVersionName,
                 long installedVersionCode, String installedSignatureSha256,
                 boolean waFolderPresent, boolean parkFolderPresent, boolean keptDatabasesPresent,
                 ParkManifest manifest, String keptDatabasesPath) {
        this(hasAllFilesAccess, whatsAppInstalled, installedVersionName, installedVersionCode,
                installedSignatureSha256, waFolderPresent, parkFolderPresent, keptDatabasesPresent,
                manifest, keptDatabasesPath, false, 0L, "none");
    }

    /**
     * The facts as they were before the record existed. A kept copy is taken to
     * match its manifest, which is what every caller of this constructor meant.
     */
    public Facts(boolean hasAllFilesAccess, boolean whatsAppInstalled, String installedVersionName,
                 long installedVersionCode, String installedSignatureSha256,
                 boolean waFolderPresent, boolean parkFolderPresent, boolean keptDatabasesPresent,
                 ParkManifest manifest, String keptDatabasesPath, boolean whatsAppDataKept,
                 long newestBackupMs, String newestBackupCrypt) {
        this(hasAllFilesAccess, whatsAppInstalled, installedVersionName, installedVersionCode,
                installedSignatureSha256, waFolderPresent, parkFolderPresent, keptDatabasesPresent,
                manifest, keptDatabasesPath, whatsAppDataKept, newestBackupMs, newestBackupCrypt,
                null, false, false, false, false, keptDatabasesPresent, 0L, 0L, null);
    }

    private Facts(boolean hasAllFilesAccess, boolean whatsAppInstalled, String installedVersionName,
                  long installedVersionCode, String installedSignatureSha256,
                  boolean waFolderPresent, boolean parkFolderPresent, boolean keptDatabasesPresent,
                  ParkManifest manifest, String keptDatabasesPath, boolean whatsAppDataKept,
                  long newestBackupMs, String newestBackupCrypt, MoveRecord record,
                  boolean recordUnreadable, boolean savedPresent, boolean savedMatches,
                  boolean savedTmpPresent, boolean keptMatches, long installedFirstInstallMs,
                  long newestBackupSize, String newestBackupName) {
        this.hasAllFilesAccess = hasAllFilesAccess;
        this.whatsAppInstalled = whatsAppInstalled;
        this.installedVersionName = installedVersionName;
        this.installedVersionCode = installedVersionCode;
        this.installedSignatureSha256 = installedSignatureSha256;
        this.waFolderPresent = waFolderPresent;
        this.parkFolderPresent = parkFolderPresent;
        this.keptDatabasesPresent = keptDatabasesPresent;
        this.manifest = manifest;
        this.keptDatabasesPath = keptDatabasesPath;
        this.whatsAppDataKept = whatsAppDataKept;
        this.newestBackupMs = newestBackupMs;
        this.newestBackupCrypt = newestBackupCrypt;
        this.record = record;
        this.recordUnreadable = recordUnreadable;
        this.savedPresent = savedPresent;
        this.savedMatches = savedMatches;
        this.savedTmpPresent = savedTmpPresent;
        this.keptMatches = keptMatches;
        this.installedFirstInstallMs = installedFirstInstallMs;
        this.newestBackupSize = newestBackupSize;
        this.newestBackupName = newestBackupName;
    }

    /** These facts plus the move's own. Migration.facts() and the tests build them this way. */
    public Facts withMove(MoveRecord record, boolean recordUnreadable, boolean savedPresent,
                          boolean savedMatches, boolean savedTmpPresent, boolean keptMatches,
                          long installedFirstInstallMs, long newestBackupSize, String newestBackupName) {
        return new Facts(hasAllFilesAccess, whatsAppInstalled, installedVersionName, installedVersionCode,
                installedSignatureSha256, waFolderPresent, parkFolderPresent, keptDatabasesPresent,
                manifest, keptDatabasesPath, whatsAppDataKept, newestBackupMs, newestBackupCrypt,
                record, recordUnreadable, savedPresent, savedMatches, savedTmpPresent, keptMatches,
                installedFirstInstallMs, newestBackupSize, newestBackupName);
    }

    /**
     * The certificate of the build the backup left: the manifest's, or the record's
     * when nothing was parked. Null when neither exists.
     */
    public String originalSignature() {
        if (manifest != null) {
            return manifest.waSignatureSha256;
        }
        return record != null ? record.waSignatureSha256 : null;
    }

    /**
     * True when the installed WhatsApp is the one that was parked from -- the
     * one whose uninstall would delete the folder again. An unreadable signature
     * is treated as possibly the original, so put-back is refused rather than risk
     * deleting the only copy.
     */
    public boolean installedIsTheOriginal() {
        String original = originalSignature();
        if (!whatsAppInstalled || original == null) {
            return false;
        }
        // Unreadable signature is treated as possibly original — refuse to put back
        if (installedSignatureSha256 == null || installedSignatureSha256.isEmpty()) {
            return true;
        }
        return original.equalsIgnoreCase(installedSignatureSha256);
    }

    /** The original is installed, but not the install the record saw: removed and installed again. */
    public boolean originalReinstalled() {
        return record != null && installedIsTheOriginal()
                && record.waFirstInstallMs != installedFirstInstallMs;
    }

    /** A copy a put-back can use when WhatsApp's folder has been deleted again. */
    public boolean copyAvailable() {
        return (savedPresent && savedMatches)
                || (keptDatabasesPresent && keptMatches && manifest != null);
    }

    /**
     * The backup database the last put-back put in WhatsApp's folder: the saved
     * copy's, or the parked one's (which Databases-kept holds too). Null when nothing
     * was put back that holds one.
     */
    public ParkManifest.Db putBackBackup() {
        if (record != null && record.restoredFrom == MoveRecord.Source.SAVED) {
            return record.savedBackup();
        }
        return manifest != null ? Verify.newestCrypt(manifest.databases) : null;
    }

    /**
     * The oldest WhatsApp that can read this move's backup: the newer of the two
     * that wrote one (the park's, and the saved copy's). The newer of the two because
     * either may be the one put back, and refusing a build that could have read the
     * other is the safe way to be wrong.
     */
    public long backupVersionCode() {
        long code = manifest != null ? manifest.waVersionCode : 0L;
        if (record != null && record.waVersionCode > code) {
            code = record.waVersionCode;
        }
        return code;
    }

    /** The version name that goes with backupVersionCode(); null when there is no move. */
    public String backupVersionName() {
        if (record != null && (manifest == null || record.waVersionCode > manifest.waVersionCode)) {
            return record.waVersionName;
        }
        return manifest != null ? manifest.waVersionName : null;
    }
}
