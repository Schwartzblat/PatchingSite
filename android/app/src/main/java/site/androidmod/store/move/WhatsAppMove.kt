package site.androidmod.store.move

import com.patcher.migrate.Facts
import com.patcher.migrate.MoveRecord
import com.patcher.migrate.Status
import com.patcher.migrate.Step
import site.androidmod.store.install.VersionOrder
import site.androidmod.store.install.Versions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val WHATSAPP_PACKAGE = "com.whatsapp"

/**
 * A backup counts as fresh only when it was made after the user tapped Start and
 * is younger than this. The start rule is what matters: on the first device run a
 * backup made minutes before the guide opened was accepted, and the messages after
 * it would have stayed behind. The hour caps a start left lying around for days,
 * after which the nightly backup would otherwise count.
 */
const val FRESH_BACKUP_MILLIS = 60 * 60 * 1000L

/**
 * How long a Start counts, both for saving the backup and for "Your backup was
 * deleted". A day: long enough for a move left overnight, short enough that a
 * Start from last week cannot claim tonight's automatic backup, or a WhatsApp
 * removed for some other reason, as part of a move.
 */
const val MOVE_START_WINDOW_MILLIS = 24 * 60 * 60 * 1000L

/**
 * Which way the chats move. Read from the phone like the step, never chosen: the
 * manifest records the signature of the build the backup was parked from, and
 * before anything is parked the installed WhatsApp is the one there is to leave.
 * See [moveDirection].
 */
enum class MoveDirection {
    /** From the official WhatsApp into ours: the move the guide was first built for. */
    TO_OURS,

    /** From ours back to the official WhatsApp, which the Play Store installs. */
    TO_OFFICIAL;

    /** The steps the user sees, in order. A step's number is its place here. */
    val steps: List<GuideStep>
        get() = when (this) {
            TO_OURS -> GuideStep.entries
            // Nothing to download: the Play Store fetches the official app itself.
            TO_OFFICIAL -> GuideStep.entries - GuideStep.DOWNLOAD
        }

    fun numberOf(step: GuideStep): Int = steps.indexOf(step) + 1
}

/** Every step either direction shows. The titles differ only where the two moves do. */
enum class GuideStep {
    ALLOW_ACCESS,
    BACK_UP,
    DOWNLOAD,
    REMOVE_OLD,
    INSTALL_NEW,
    RESTORE,
    FINISH;

    fun title(direction: MoveDirection): String {
        val back = direction == MoveDirection.TO_OFFICIAL
        return when (this) {
            ALLOW_ACCESS -> "Allow file access"
            BACK_UP -> "Back up your chats in WhatsApp"
            DOWNLOAD -> "Download the new WhatsApp"
            REMOVE_OLD -> if (back) "Remove our WhatsApp" else "Remove the old WhatsApp"
            INSTALL_NEW -> if (back) "Install WhatsApp from the Play Store" else "Install the new WhatsApp"
            RESTORE -> "Restore your chats"
            FINISH -> "Finish up"
        }
    }
}

/** The same step can need different words depending on how the user got there. */
enum class StepVariant {
    NORMAL,

    /** Step 1, mid-move: access was revoked after the backup was parked. */
    ACCESS_REVOKED,

    /** Removing the old app once the backup is parked: only the uninstall is left. */
    BACKUP_SAVED,

    /** Removing the old app after an uninstall with "Keep app data" ticked. */
    DATA_KEPT,

    /** Installing ours when the build is no longer on disk (the store forgets downloads when its process dies). */
    NEEDS_DOWNLOAD,

    /**
     * Going back: WhatsApp is installed and the backup is still parked, because
     * WhatsAppInstallWatcher did not run (the store's process was dead).
     */
    PUT_BACK_NOW,

    /**
     * Going back: the installed official WhatsApp is older than the backup, which it
     * would refuse. The backup stays parked until the Play Store updates it.
     */
    PLAY_TOO_OLD,

    /**
     * Finish up, before it can finish: the user said their chats are back, and the
     * new WhatsApp has not yet made a backup of its own to prove it.
     */
    LAST_CHECK,

    /**
     * Finish up, stopped: the new WhatsApp made a backup after the put-back, but
     * under 3/4 of the one put back. Some chats may not have come back, so nothing
     * is offered for removal and the user is sent to us.
     */
    BACKUP_TOO_SMALL,
}

sealed interface MovePhase {
    /** Nothing to move: no WhatsApp is installed and nothing is parked. No card. */
    data object NothingToDo : MovePhase

    /** The official WhatsApp is newer than our newest build, which could not restore its backup. */
    data class NotPossibleYet(val installed: String, val newest: String) : MovePhase

    /** The engine stopped, or the device is in a state the guide has no words for. */
    data object Attention : MovePhase

    /**
     * WhatsApp was removed before its backup could be saved, during a move the user
     * had started: the 2026-10-10 loss, which used to read as "Nothing to move". Only
     * a Google Drive backup, if there is one, is left, and the guide says so.
     */
    data object RemovedBeforeSaved : MovePhase

    data class AtStep(val step: GuideStep, val variant: StepVariant = StepVariant.NORMAL) : MovePhase
}

enum class BackupAge { NONE, FRESH, STALE }

/**
 * What the user has told the guide; memory only, so a dead process just asks again.
 * "My chats are back" is not here: the engine's record holds it (MoveRecord), so it
 * survives the process and the finish can wait days for the proof.
 */
data class MoveAcks(
    val oldBackup: Boolean = false,
    val password: Boolean = false,
    /**
     * The direction of a move whose spare copy was removed in this session, for the
     * "All done" panel. A direction rather than a flag because by then the manifest
     * is gone, and the phone alone reads as the start of the opposite move.
     */
    val finished: MoveDirection? = null,
)

/** The saved copy of the backup, for "Your backup is saved in a safe place ✓". */
data class SavedBackup(val atMs: Long, val bytes: Long)

data class MoveGuide(
    val phase: MovePhase,
    val direction: MoveDirection,
    /**
     * A move has started and is not finished: a saved copy, a parked backup, or one
     * put back. The cards say "Finish moving your chats".
     */
    val inProgress: Boolean,
    /**
     * The official WhatsApp is installed and its backup is still in its own folder:
     * removing it would lose the photos and videos, and, before a copy is saved, the
     * chats too.
     */
    val officialAtRisk: Boolean,
    /** The same for ours, while going back is on offer. */
    val oursAtRisk: Boolean,
    /** Set once a move finished in this session: the guide shows "All done" in that move's words. */
    val finished: MoveDirection?,
    val backupAge: BackupAge,
    val newestBackupMs: Long,
    /** When the backup WhatsApp will be offered was made, for the return's "check its date"; 0 when none. */
    val savedBackupMs: Long,
    /** The installed WhatsApp's version, and the version the move's backup came from. */
    val installedVersion: String?,
    val savedVersion: String?,
    val encrypted: Boolean,
    val backupDone: Boolean,
    val downloadDone: Boolean,
    /** The backup has left WhatsApp's folder (parked, or put back). Steps 2-3 tick by position after that. */
    val pastBackup: Boolean = inProgress,
    /** The saved copy; null while there is none, or it no longer matches its record. */
    val saved: SavedBackup? = null,
    /** The backup goes back, or went back, from a copy: WhatsApp was removed before step 4, and its media with it. */
    val mediaLost: Boolean = false,
    /** The original WhatsApp was removed and installed again during the move. */
    val reinstalled: Boolean = false,
    val canCancel: Boolean = false,
    /** The backup put back, and the new WhatsApp's own: Finish up's sizes. */
    val putBackSize: Long = 0,
    val newBackupSize: Long = 0,
) {
    val current: GuideStep? get() = (phase as? MovePhase.AtStep)?.step

    /** Settings offers going back: ours is installed and nothing has moved, or a return is under way. */
    val offersReturn: Boolean get() = oursAtRisk || (inProgress && direction == MoveDirection.TO_OFFICIAL)

    /** Whether to tick [step] in the checklist. */
    fun isDone(step: GuideStep): Boolean {
        val at = current ?: return false
        if (at == GuideStep.ALLOW_ACCESS) return false
        val steps = direction.steps
        val atIndex = steps.indexOf(at)
        // The move in's steps 2 and 3 can be done in either order, so before the
        // backup leaves WhatsApp's folder each is ticked on its own evidence rather
        // than by position. A saved copy alone does not change that.
        if (direction == MoveDirection.TO_OURS && !pastBackup && atIndex <= steps.indexOf(GuideStep.REMOVE_OLD)) {
            if (step == GuideStep.BACK_UP) return backupDone
            if (step == GuideStep.DOWNLOAD) return downloadDone
        }
        return steps.indexOf(step) in 0 until atIndex
    }

    companion object {
        val Empty = MoveGuide(
            phase = MovePhase.NothingToDo,
            direction = MoveDirection.TO_OURS,
            inProgress = false,
            officialAtRisk = false,
            oursAtRisk = false,
            finished = null,
            backupAge = BackupAge.NONE,
            newestBackupMs = 0,
            savedBackupMs = 0,
            installedVersion = null,
            savedVersion = null,
            encrypted = false,
            backupDone = false,
            downloadDone = false,
            pastBackup = false,
        )
    }
}

/** Engine steps that mean a backup has left WhatsApp's folder and not yet been signed off. */
private val InProgressSteps = setOf(
    Step.UNINSTALL_WHATSAPP, Step.PUT_BACK, Step.INSTALL_PATCHED,
    Step.VERIFY_AND_RESTORE, Step.DONE, Step.NEEDS_ATTENTION,
)

/**
 * Which way the move goes. With access, the move's own certificate is the truth:
 * the manifest's, or the record's before anything was parked. Without either, the
 * installed WhatsApp is the one to leave. Without access neither can be read, and
 * only the stored hint can say which way a move under way is going. A hint written
 * before going back existed has no direction, which reads as TO_OURS: right, it
 * was the only way there was.
 */
fun moveDirection(
    status: Status,
    ours: Boolean,
    parkedFromOurs: Boolean,
    moveActive: Boolean,
    toOfficialHint: Boolean,
): MoveDirection {
    val f = status.facts
    val back = when {
        f.hasAllFilesAccess && (f.manifest != null || f.record != null) -> parkedFromOurs
        !f.hasAllFilesAccess && moveActive -> toOfficialHint
        else -> ours
    }
    return if (back) MoveDirection.TO_OFFICIAL else MoveDirection.TO_OURS
}

/**
 * Whether the backup should go back now that a WhatsApp was installed outside the
 * store's install gate: by the Play Store, on the way back, or over a folder
 * emptied by removing WhatsApp by hand.
 *
 * The engine's canPutBack already refuses the build the backup left, anything in
 * WhatsApp's folder, and a missing grant, so this rule is the same in both
 * directions and for every source (parked or a copy). What it adds: an app removed
 * with its data kept is not an install, and an installed WhatsApp older than the
 * backup would refuse it - only after the number is verified, with the backup by
 * then where removing WhatsApp deletes it. Left where it is, it waits for the Play
 * Store's update instead.
 */
fun shouldPutBackOnInstall(status: Status): Boolean {
    val f = status.facts
    if (f.manifest == null && f.record == null) return false
    return status.canPutBack && f.whatsAppInstalled && !f.whatsAppDataKept &&
        f.installedVersionCode >= f.backupVersionCode()
}

/**
 * Whether the store should ask the engine to save the backup now. True for the
 * backup step 2 accepts (fresh by FRESH_BACKUP_MILLIS, or the older one the user
 * chose), while the move is at its start and the WhatsApp being left - whose
 * removal deletes the backup - is still installed. Run on every open
 * (MoveRepository.track). The engine decides the rest: whether a newer backup may
 * replace a copy already saved.
 *
 * The Start's age only gates the first copy: once a record exists the move is already
 * under way, however long the user takes, and the engine's identity, newer and 3/4-size
 * rules guard any replacement - so a move older than a day keeps saving newer backups.
 */
fun shouldSaveBackup(status: Status, startedAt: Long, now: Long, oldBackupChosen: Boolean): Boolean {
    val f = status.facts
    if (!f.hasAllFilesAccess || status.step != Step.BACK_UP_AND_PARK) return false
    if (!f.whatsAppInstalled || f.whatsAppDataKept || f.installedSignatureSha256.isNullOrEmpty()) return false
    if (f.record == null && (startedAt <= 0 || now - startedAt > MOVE_START_WINDOW_MILLIS)) return false
    if (f.newestBackupMs <= 0) return false
    val fresh = f.newestBackupMs >= startedAt && now - f.newestBackupMs < FRESH_BACKUP_MILLIS
    if (!fresh && !oldBackupChosen) return false
    val saved = f.record?.savedBackup() ?: return true
    // Made again when the copy went missing or stopped matching, even with no newer
    // backup to take its place.
    return f.newestBackupMs > saved.mtimeMs || !(f.savedPresent && f.savedMatches)
}

/** Engine steps after the backup has left the build it was parked from: it is parked, or back in WhatsApp's folder. */
private val PastTheOriginalSteps = setOf(Step.PUT_BACK, Step.INSTALL_PATCHED, Step.VERIFY_AND_RESTORE, Step.DONE)

/**
 * Whether the install gate must refuse a store install of ours before anything
 * moves. The store only installs ours, which can never install over a WhatsApp
 * signed by someone else. So while a move is past its removal step and such a
 * WhatsApp is installed - the official one, mid-return - the install can only fail
 * on its certificate, and that failure offers "Uninstall and retry", which deletes
 * WhatsApp's folder: the backup the gate would have just put there, or the one
 * already back in it. Found by the 2026-10-09 review; before going back existed,
 * only a third-party build could be in that state.
 *
 * UNINSTALL_WHATSAPP is left out on purpose: there the installed app is the one the
 * backup is still parked from, removing it is the guide's own next step, and
 * reinstalling ours over its kept data is the return's "kept its data" fix.
 */
fun storeInstallWouldStrandBackup(status: Status, ours: Boolean): Boolean {
    val f = status.facts
    return f.hasAllFilesAccess && f.whatsAppInstalled && !ours && status.step in PastTheOriginalSteps
}

/**
 * Engine status plus what only the store knows, turned into what the guide shows.
 *
 * Pure, and the only place these decisions are made, so the WhatsApp page card,
 * the Apps banner, the Library warnings, Settings and the guide itself can never
 * disagree. The engine's own sentences are deliberately not used: they name
 * folders and manifests, and the guide words everything itself from the step and
 * the facts.
 *
 * [ours] is whether the installed WhatsApp carries the store's signing key;
 * [parkedFromOurs] whether the move's own certificate (the manifest's, or the
 * record's) does. [toOfficialHint] is the stored direction of a move under way.
 * [newestVersion] is null until the build list has loaded. [startedAt] is when the
 * user tapped Start; see [FRESH_BACKUP_MILLIS]. [seenInstalledAt] is when the store
 * last saw the WhatsApp being left installed after that Start; see
 * SettingsStore.whatsAppMoveSeenInstalledAt.
 */
fun moveGuide(
    status: Status,
    ours: Boolean,
    parkedFromOurs: Boolean,
    moveActive: Boolean,
    toOfficialHint: Boolean,
    newestVersion: String?,
    downloaded: Boolean,
    now: Long,
    startedAt: Long,
    acks: MoveAcks,
    seenInstalledAt: Long = 0L,
): MoveGuide {
    val f = status.facts
    val manifest = f.manifest
    val record = f.record
    val direction = moveDirection(status, ours, parkedFromOurs, moveActive, toOfficialHint)
    val backupAge = when {
        f.newestBackupMs <= 0 -> BackupAge.NONE
        f.newestBackupMs >= startedAt && now - f.newestBackupMs < FRESH_BACKUP_MILLIS -> BackupAge.FRESH
        else -> BackupAge.STALE
    }
    val encrypted = "crypt15" == (manifest?.crypt ?: record?.crypt ?: f.newestBackupCrypt)
    val backupDone = (backupAge == BackupAge.FRESH || (backupAge == BackupAge.STALE && acks.oldBackup)) &&
        (!encrypted || acks.password)

    // Without access the engine can see no folder at all, so only the stored hint
    // can say a move is under way.
    val pastBackup = if (f.hasAllFilesAccess) status.step in InProgressSteps else moveActive
    // A saved copy is a move under way too, though the backup is still in WhatsApp's
    // folder: the cards say "Finish", and a new Start must not move the mark.
    val inProgress = pastBackup || (f.hasAllFilesAccess && record != null)
    val official = f.whatsAppInstalled && !f.whatsAppDataKept && !ours

    val phase = when {
        removedBeforeSaved(status, now, startedAt, seenInstalledAt) -> MovePhase.RemovedBeforeSaved
        direction == MoveDirection.TO_OURS ->
            toOursPhase(status, ours, official, moveActive, newestVersion, downloaded, backupDone, acks)
        else -> toOfficialPhase(status, moveActive, backupDone, acks)
    }
    val putBack = f.putBackBackup()

    return MoveGuide(
        phase = phase,
        direction = direction,
        inProgress = inProgress,
        // At risk while the backup is still in WhatsApp's own folder, saved copy or not:
        // removing WhatsApp still deletes its media, so the Library keeps asking first.
        officialAtRisk = official && !pastBackup,
        oursAtRisk = ours && !pastBackup && direction == MoveDirection.TO_OFFICIAL,
        finished = acks.finished?.takeIf { !inProgress },
        backupAge = backupAge,
        newestBackupMs = f.newestBackupMs,
        // The backup WhatsApp will offer: the one put back, else the parked one (the
        // same rule as Verify.cryptOf), else the saved one.
        savedBackupMs = (putBack ?: record?.savedBackup())?.mtimeMs ?: 0L,
        installedVersion = f.installedVersionName,
        savedVersion = f.backupVersionName(),
        encrypted = encrypted,
        backupDone = backupDone,
        downloadDone = downloaded,
        pastBackup = pastBackup,
        saved = record?.savedBackup()
            ?.takeIf { f.savedPresent && f.savedMatches }
            ?.let { SavedBackup(it.mtimeMs, it.size) },
        mediaLost = (status.step == Step.PUT_BACK && !f.parkFolderPresent) ||
            record?.restoredFrom == MoveRecord.Source.SAVED || record?.restoredFrom == MoveRecord.Source.KEPT,
        reinstalled = manifest == null && f.originalReinstalled(),
        canCancel = status.canCancel,
        putBackSize = putBack?.size ?: 0L,
        newBackupSize = record?.evidenceSize?.takeIf { it > 0 } ?: f.newestBackupSize,
    )
}

/**
 * The 2026-10-10 loss, recognised: a move the user started in the last day, on a
 * phone where the store had seen the WhatsApp being left still installed, and now
 * no WhatsApp, no record and nothing parked. Without the "seen installed" fact,
 * anyone who opened the guide with no WhatsApp at all would be told their backup
 * was deleted.
 */
private fun removedBeforeSaved(status: Status, now: Long, startedAt: Long, seenInstalledAt: Long): Boolean {
    val f = status.facts
    return f.hasAllFilesAccess && status.step == Step.NOTHING_TO_MIGRATE && f.record == null &&
        f.manifest == null && startedAt > 0 && now - startedAt < MOVE_START_WINDOW_MILLIS &&
        seenInstalledAt >= startedAt
}

/**
 * The backup is back and the new WhatsApp installed: Restore until the user says
 * their chats are back, then the last check, which only the new WhatsApp's own
 * backup ends (the engine's DONE). A backup that came out much smaller than the
 * one put back stops it there instead.
 */
private fun verifyPhase(f: Facts): MovePhase {
    val record = f.record
    if (record == null || record.chatsConfirmedAtMs <= 0) return MovePhase.AtStep(GuideStep.RESTORE)
    return MovePhase.AtStep(
        GuideStep.FINISH,
        if (newBackupTooSmall(f)) StepVariant.BACKUP_TOO_SMALL else StepVariant.LAST_CHECK,
    )
}

/** A backup newer than the put-back, under 3/4 of it: the same bar Finishing.findEvidence sets. */
private fun newBackupTooSmall(f: Facts): Boolean {
    val record = f.record ?: return false
    val putBack = f.putBackBackup() ?: return false
    return f.newestBackupMs > record.restoredAtMs && f.newestBackupSize * 4 < putBack.size * 3
}

/** The move into ours, as the 2026-10-04 spec's table has it, with the 2026-10-10 rows. */
private fun toOursPhase(
    status: Status,
    ours: Boolean,
    official: Boolean,
    moveActive: Boolean,
    newestVersion: String?,
    downloaded: Boolean,
    backupDone: Boolean,
    acks: MoveAcks,
): MovePhase {
    val f = status.facts
    val signatureReadable = !f.installedSignatureSha256.isNullOrEmpty()
    // Only meaningful before anything moved: WhatsApp refuses a backup made by a
    // newer version of itself, and it says so after the number is verified.
    val tooNew = official && newestVersion != null && !f.installedVersionName.isNullOrBlank() &&
        Versions.compare(newestVersion, f.installedVersionName) == VersionOrder.OLDER
    val install = if (downloaded) StepVariant.NORMAL else StepVariant.NEEDS_DOWNLOAD

    if (!f.hasAllFilesAccess) {
        return when {
            moveActive -> MovePhase.AtStep(GuideStep.ALLOW_ACCESS, StepVariant.ACCESS_REVOKED)
            !official -> MovePhase.NothingToDo
            tooNew -> MovePhase.NotPossibleYet(f.installedVersionName, newestVersion!!)
            else -> MovePhase.AtStep(GuideStep.ALLOW_ACCESS)
        }
    }
    return when (status.step) {
        Step.NEEDS_ATTENTION -> MovePhase.Attention
        Step.NOTHING_TO_MIGRATE -> MovePhase.NothingToDo
        Step.BACK_UP_AND_PARK -> when {
            // Ours installed reads as TO_OFFICIAL before it gets here; kept for a
            // manifest that names the official app while ours is installed.
            ours -> MovePhase.NothingToDo
            // Parking would be refused, and "the certificate could not be read"
            // is not something a user can act on.
            !signatureReadable -> MovePhase.Attention
            tooNew -> MovePhase.NotPossibleYet(f.installedVersionName, newestVersion!!)
            !backupDone -> MovePhase.AtStep(GuideStep.BACK_UP)
            !downloaded -> MovePhase.AtStep(GuideStep.DOWNLOAD)
            else -> MovePhase.AtStep(GuideStep.REMOVE_OLD)
        }
        Step.UNINSTALL_WHATSAPP -> when {
            f.whatsAppDataKept -> MovePhase.AtStep(GuideStep.REMOVE_OLD, StepVariant.DATA_KEPT)
            !signatureReadable -> MovePhase.Attention
            else -> MovePhase.AtStep(GuideStep.REMOVE_OLD, StepVariant.BACKUP_SAVED)
        }
        // Something already sits where the backup has to go. The engine refuses to
        // merge two folders, and so does the guide. Otherwise the backup goes back -
        // parked, or a copy when WhatsApp was removed by hand - before ours installs.
        Step.PUT_BACK -> if (f.waFolderPresent) MovePhase.Attention else MovePhase.AtStep(GuideStep.INSTALL_NEW, install)
        // The backup is back in place but the original came back too: removing it
        // now would delete the folder again, so a person has to look at this.
        Step.INSTALL_PATCHED -> if (f.installedIsTheOriginal()) {
            MovePhase.Attention
        } else {
            MovePhase.AtStep(GuideStep.INSTALL_NEW, install)
        }
        Step.VERIFY_AND_RESTORE -> verifyPhase(f)
        Step.DONE -> MovePhase.AtStep(GuideStep.FINISH)
        else -> MovePhase.NothingToDo
    }
}

/**
 * Going back to the official WhatsApp: the 2026-10-09 spec's table, with the
 * 2026-10-10 rows.
 *
 * The Play Store installs the official app, so the backup stays parked until it
 * has, and WhatsAppInstallWatcher puts it back the moment it is installed. "Other"
 * below is a WhatsApp that is not the build the backup came from (the engine's
 * "original"): on this path, the official one. "Compatible" is the engine's own
 * rule (Migration.checkCompatible) against the move's backup version, compared
 * here so this file never loads Migration, which needs Android.
 */
private fun toOfficialPhase(status: Status, moveActive: Boolean, backupDone: Boolean, acks: MoveAcks): MovePhase {
    val f = status.facts
    if (!f.hasAllFilesAccess) {
        return MovePhase.AtStep(
            GuideStep.ALLOW_ACCESS,
            if (moveActive) StepVariant.ACCESS_REVOKED else StepVariant.NORMAL,
        )
    }
    val otherInstalled = f.whatsAppInstalled && !f.whatsAppDataKept && !f.installedIsTheOriginal()
    val compatible = (f.manifest != null || f.record != null) && f.installedVersionCode >= f.backupVersionCode()
    return when (status.step) {
        Step.NEEDS_ATTENTION -> MovePhase.Attention
        Step.BACK_UP_AND_PARK -> if (backupDone) {
            MovePhase.AtStep(GuideStep.REMOVE_OLD)
        } else {
            MovePhase.AtStep(GuideStep.BACK_UP)
        }
        Step.UNINSTALL_WHATSAPP -> when {
            f.whatsAppDataKept -> MovePhase.AtStep(GuideStep.REMOVE_OLD, StepVariant.DATA_KEPT)
            f.installedSignatureSha256.isNullOrEmpty() -> MovePhase.Attention
            else -> MovePhase.AtStep(GuideStep.REMOVE_OLD, StepVariant.BACKUP_SAVED)
        }
        Step.PUT_BACK -> when {
            // WhatsApp's folder is no longer empty: most likely the official app was
            // opened and set up before the backup went back. The engine will not merge.
            f.waFolderPresent -> MovePhase.Attention
            !otherInstalled -> MovePhase.AtStep(GuideStep.INSTALL_NEW)
            compatible -> MovePhase.AtStep(GuideStep.INSTALL_NEW, StepVariant.PUT_BACK_NOW)
            else -> MovePhase.AtStep(GuideStep.INSTALL_NEW, StepVariant.PLAY_TOO_OLD)
        }
        // Back in WhatsApp's folder with nothing installed: ours was reinstalled from
        // the store mid-return (the gate put the backup back) and that install failed.
        // The Play Store is still the way on. With ours installed over it, a person
        // has to look.
        Step.INSTALL_PATCHED -> if (!f.whatsAppInstalled && f.waFolderPresent) {
            MovePhase.AtStep(GuideStep.INSTALL_NEW)
        } else {
            MovePhase.Attention
        }
        // The watcher never puts a backup back in front of an older app, so this is a
        // backup that got there some other way: WhatsApp would refuse it.
        Step.VERIFY_AND_RESTORE -> if (!compatible) MovePhase.Attention else verifyPhase(f)
        Step.DONE -> MovePhase.AtStep(GuideStep.FINISH)
        Step.NOTHING_TO_MIGRATE -> MovePhase.NothingToDo
        else -> MovePhase.NothingToDo
    }
}

/**
 * "today at 02:09", "yesterday at 23:52", "1 Oct at 20:04". Locale.US and a fixed
 * pattern for the same reason formatBytes uses them: every other word is English.
 */
fun backupTime(millis: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time = at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
    val day: LocalDate = at.toLocalDate()
    return when (day) {
        today -> "today at $time"
        today.minusDays(1) -> "yesterday at $time"
        else -> "${at.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))} at $time"
    }
}

/** "just now", "1 minute ago", "12 minutes ago": only used for a fresh backup, so never hours. */
fun backupAgo(millis: Long, now: Long): String {
    val minutes = ((now - millis) / 60_000).coerceAtLeast(0)
    return when (minutes) {
        0L -> "just now"
        1L -> "1 minute ago"
        else -> "$minutes minutes ago"
    }
}
