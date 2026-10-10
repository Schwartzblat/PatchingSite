package site.androidmod.store.move

import android.content.Context
import android.content.pm.PackageManager
import com.patcher.migrate.Facts
import com.patcher.migrate.Migration
import com.patcher.migrate.Result
import com.patcher.migrate.SpareCopy
import com.patcher.migrate.Status
import com.patcher.migrate.Step
import site.androidmod.store.data.SettingsStore
import site.androidmod.store.install.VersionOrder
import site.androidmod.store.install.Versions
import java.io.File
import java.security.MessageDigest

/** What the install path is told before it installs WhatsApp. */
sealed interface InstallGate {
    data object Proceed : InstallGate
    data class Refused(val message: String) : InstallGate
}

/** Why the backup could not be saved the last time the store tried, for the guide's steps 2-4. */
sealed interface SaveProblem {
    /** The phone is too full: the copy needs [neededBytes], margin included. */
    data class NoRoom(val neededBytes: Long) : SaveProblem

    /** The copy or its check failed: the engine's words, for Details and the support email. */
    data class Failed(val details: List<String>) : SaveProblem
}

/**
 * The store's one door to the move engine (`:migrate`). Everything here blocks on
 * disk or PackageManager, so callers run it off the main thread.
 */
class MoveRepository(private val context: Context, private val settings: SettingsStore) {

    private val migration = Migration(context)

    /**
     * Every write to the move, one at a time: the copy, the park, each put-back (the
     * install gate's, the watcher's, the guide's button), the confirmation, cancel
     * and finish. See OneAtATime for why waiting beats the engine's own refusal.
     */
    private val oneAtATime = OneAtATime()

    /**
     * Set while park() runs, so a status() racing it from a screen's resume cannot
     * read the not-yet-parked folder as "nothing started" and clear the hint that
     * park() has just set.
     */
    @Volatile
    private var parking = false

    /** Why the last save failed; null once one succeeds. Shown by the guide's steps 2-4. */
    @Volatile
    var saveProblem: SaveProblem? = null
        private set

    /** The store's own certificate digest, hashed exactly as the engine hashes WhatsApp's. */
    private val ownSignature: String? by lazy {
        try {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
                ?.let { sha256(it.toByteArray()) }
        } catch (error: PackageManager.NameNotFoundException) {
            null
        }
    }

    val moveActive: Boolean get() = settings.whatsAppMoveActive

    val startedAt: Long get() = settings.whatsAppMoveStartedAt

    /** The stored direction of a move under way; see SettingsStore.whatsAppMoveToOfficial. */
    val moveToOfficial: Boolean get() = settings.whatsAppMoveToOfficial

    /** See SettingsStore.whatsAppMoveSeenInstalledAt. */
    val seenInstalledAt: Long get() = settings.whatsAppMoveSeenInstalledAt

    /**
     * Records the user starting the guide, unless a start under an hour old is
     * already on record: coming back to the guide shortly after making the backup
     * must not invalidate it. An older start is a new attempt and moves the mark.
     */
    fun markStarted(now: Long) {
        val previous = settings.whatsAppMoveStartedAt
        if (previous == 0L || now - previous > FRESH_BACKUP_MILLIS) {
            settings.whatsAppMoveStartedAt = now
        }
    }

    /**
     * Keeps the move safe between screens. Run on every open of the store
     * (MainActivity.onStart) and before every read of the guide (MoveViewModel.refresh):
     * - at the move's start, saves the backup the guide accepts for step 2
     *   (shouldSaveBackup). That copy is what brings the chats back when WhatsApp is
     *   removed from Settings instead of through step 4, as it was on 2026-10-10;
     * - once the backup is back, records the new WhatsApp's own backup as the proof.
     * Null when there was nothing to do, the usual answer.
     */
    fun track(oldBackupChosen: Boolean = false, now: Long = System.currentTimeMillis()): Result? =
        oneAtATime.run {
            val status = migration.status()
            noteSeenInstalled(status, now)
            when {
                shouldSaveBackup(status, settings.whatsAppMoveStartedAt, now, oldBackupChosen) -> {
                    val result = migration.saveBackup()
                    saveProblem = when {
                        result.ok -> null
                        result.neededBytes > 0 -> SaveProblem.NoRoom(result.neededBytes)
                        else -> SaveProblem.Failed(listOf(result.summary) + result.details)
                    }
                    // The record is on disk now. Without file access it cannot be read, and
                    // the hint is all that says a move began; see SettingsStore.whatsAppMoveActive.
                    if (result.ok) setHint(status.facts)
                    result
                }
                status.step == Step.VERIFY_AND_RESTORE -> migration.recordEvidence()
                else -> null
            }
        }

    /**
     * Notes that the WhatsApp being left was installed after the user's Start. That
     * is what later lets the guide say "Your backup was deleted" rather than "Nothing
     * to move" (MovePhase.RemovedBeforeSaved). Written once per Start.
     */
    private fun noteSeenInstalled(status: Status, now: Long) {
        val f = status.facts
        val start = settings.whatsAppMoveStartedAt
        if (start > 0 && f.hasAllFilesAccess && status.step == Step.BACK_UP_AND_PARK &&
            f.whatsAppInstalled && !f.whatsAppDataKept && settings.whatsAppMoveSeenInstalledAt < start
        ) {
            settings.whatsAppMoveSeenInstalledAt = now
        }
    }

    /**
     * The engine's status. With access granted the disk is the whole truth, so a
     * move that has ended, or never began, clears the stored hint here. A record
     * counts as a move begun, though the step still reads as the start.
     */
    fun status(): Status {
        val status = migration.status()
        if (!parking && status.facts.hasAllFilesAccess && status.facts.record == null &&
            settings.whatsAppMoveActive &&
            (status.step == Step.BACK_UP_AND_PARK || status.step == Step.NOTHING_TO_MIGRATE)
        ) {
            clearHint()
        }
        return status
    }

    /**
     * Whether the installed WhatsApp is one of ours. Compared the way the engine
     * compares, so the two cannot disagree about which build is which. An
     * unreadable signature is not ours: that keeps the guide on the cautious side.
     */
    fun isOurs(facts: Facts): Boolean {
        val installed = facts.installedSignatureSha256 ?: return false
        val own = ownSignature ?: return false
        return facts.whatsAppInstalled && !facts.whatsAppDataKept && installed.equals(own, ignoreCase = true)
    }

    /**
     * Whether the backup left ours, which makes the move a return to the official
     * WhatsApp: the manifest's certificate, or the record's before anything was
     * parked. Compared exactly as [isOurs] compares the installed one.
     */
    fun parkedFromOurs(facts: Facts): Boolean {
        val left = facts.originalSignature() ?: return false
        val own = ownSignature ?: return false
        return left.equals(own, ignoreCase = true)
    }

    /**
     * Parks WhatsApp's folder. The hint and its direction are written first, so a
     * process killed mid-park still leaves word that a move began and which way, and
     * then set from what the engine reports, so a refused park does not leave them
     * claiming one - unless the move's record says one began anyway. Waits for a copy
     * still being made (track) rather than failing.
     */
    fun park(): Result = oneAtATime.run {
        parking = true
        // Ours is what is being left exactly when this is a return.
        settings.whatsAppMoveToOfficial = isOurs(migration.status().facts)
        settings.whatsAppMoveActive = true
        try {
            migration.park()
        } finally {
            val after = migration.status()
            val active = after.facts.record != null ||
                (after.step != Step.BACK_UP_AND_PARK && after.step != Step.NOTHING_TO_MIGRATE)
            if (!active) clearHint()
            parking = false
        }
    }

    /** "My chats are back", into the move's record so it outlives the process. */
    fun confirmChats(): Result = oneAtATime.run { migration.confirmChats() }

    /** Cancels a move at its start: the saved copy and the record go, and WhatsApp keeps its chats. */
    fun cancelMove(): Result = oneAtATime.run {
        val result = migration.cancel()
        if (result.ok) endMove()
        result
    }

    /** Removes every spare copy and both records, which is the move's last act. Only in DONE. */
    fun finish(): Result = oneAtATime.run {
        val result = migration.finish()
        if (result.ok) endMove()
        result
    }

    /** What finish() would remove, for the guide to list. Walks the folders: Finish up only. */
    fun spareCopies(): List<SpareCopy> = migration.spareCopies()

    private fun endMove() {
        clearHint()
        settings.whatsAppMoveStartedAt = 0L
        settings.whatsAppMoveSeenInstalledAt = 0L
        saveProblem = null
    }

    private fun setHint(facts: Facts) {
        // Ours is what is being left exactly when this is a return.
        settings.whatsAppMoveToOfficial = isOurs(facts)
        settings.whatsAppMoveActive = true
    }

    /** The hint and its direction are one record; they are cleared together. */
    private fun clearHint() {
        settings.whatsAppMoveActive = false
        settings.whatsAppMoveToOfficial = false
    }

    /**
     * Puts the backup back once a WhatsApp it can go into is installed (see
     * [shouldPutBackOnInstall]): the parked one, or a copy when WhatsApp's folder was
     * deleted another way. Null when there is nothing to do, the usual answer.
     *
     * Going back to the official WhatsApp depends on this: the Play Store installs
     * it, so [beforeInstall] never runs, and WhatsApp reads a local backup only while
     * it registers. One at a time with every other write, so the watcher and the
     * guide's button, racing, run one after the other: the second then finds nothing
     * to do, instead of reporting the engine's "another operation is already running"
     * as a failure.
     */
    fun putBackAfterInstall(): Result? = oneAtATime.run {
        if (shouldPutBackOnInstall(migration.status())) migration.putBack() else null
    }

    /**
     * Runs before any install of WhatsApp from the store: the guide, the version
     * cards and the Library tab all pass through LibraryViewModel.install.
     *
     * The one rule it enforces: while the backup is parked - or only a copy of it is
     * left - it goes back BEFORE WhatsApp is installed. WhatsApp reads a local backup
     * only while it registers, so a backup that arrives afterwards is never offered,
     * and that failure looks exactly like nothing happening.
     *
     * [onPuttingBack] fires just before the move, so the screen can say what the
     * pause is.
     */
    fun beforeInstall(file: File, versionName: String, onPuttingBack: () -> Unit): InstallGate =
        oneAtATime.run { gate(file, versionName, onPuttingBack) }

    private fun gate(file: File, versionName: String, onPuttingBack: () -> Unit): InstallGate {
        val status = migration.status()
        val facts = status.facts
        if (!facts.hasAllFilesAccess) {
            return if (settings.whatsAppMoveActive) {
                InstallGate.Refused(
                    "Turn file access back on first, so your WhatsApp backup can go back before " +
                        "WhatsApp is installed. Open \"Move your chats\" on the WhatsApp page."
                )
            } else {
                InstallGate.Proceed
            }
        }
        // Before the put-back: an install that cannot succeed must not move anything,
        // and its failure must not get as far as offering "Uninstall and retry".
        if (storeInstallWouldStrandBackup(status, isOurs(facts))) {
            return InstallGate.Refused(
                "The regular WhatsApp is installed, so ours can't be installed over it. Nothing was " +
                    "changed. Open \"Move your chats\" on the WhatsApp page to see what to do."
            )
        }
        if (status.canPutBack) {
            if (!compatible(file, versionName, facts)) {
                return InstallGate.Refused(
                    "This WhatsApp is older than your backup, so it couldn't restore your chats. " +
                        "Nothing was changed. Contact us and we'll help."
                )
            }
            onPuttingBack()
            val result = migration.putBack()
            return if (result.ok) {
                InstallGate.Proceed
            } else {
                InstallGate.Refused(
                    "Your backup couldn't be put back, so WhatsApp wasn't installed. Your backup is " +
                        "still on this phone. Contact us and we'll help you finish."
                )
            }
        }
        // Parked but not movable (something in the way), or a state the engine will
        // not vouch for: installing now would register WhatsApp without the backup.
        if (status.step == Step.PUT_BACK || status.step == Step.NEEDS_ATTENTION) {
            return InstallGate.Refused(
                "We paused to keep your chats safe, so WhatsApp wasn't installed. Open \"Move your " +
                    "chats\" on the WhatsApp page to see what to do."
            )
        }
        return InstallGate.Proceed
    }

    /**
     * The build's versionCode against the backup's when it can be read, which is the
     * exact rule WhatsApp applies. The backup's is the newer of the park's and the
     * saved copy's (Facts.backupVersionCode), since either may be the one put back. A
     * build the platform cannot parse as one APK (a split bundle) falls back to
     * comparing version names, so a future bundle cannot dead-end the move.
     */
    private fun compatible(file: File, versionName: String, facts: Facts): Boolean {
        val code = try {
            context.packageManager.getPackageArchiveInfo(file.path, 0)?.longVersionCode
        } catch (error: RuntimeException) {
            null
        }
        if (code != null) return code >= facts.backupVersionCode()
        val saved = facts.backupVersionName() ?: return true
        return Versions.compare(versionName, saved) != VersionOrder.OLDER
    }

    /** Plain text about the move for a support email: what support needs, nothing personal. */
    fun supportSummary(status: Status, guide: MoveGuide): String = buildString {
        val f = status.facts
        append("Guide: ").append(describe(guide)).append('\n')
        append("Direction: ")
            .append(if (guide.direction == MoveDirection.TO_OFFICIAL) "to the regular WhatsApp" else "to ours")
            .append('\n')
        append("Engine step: ").append(status.step).append('\n')
        append("File access: ").append(if (f.hasAllFilesAccess) "yes" else "no").append('\n')
        append("Move hint: ").append(settings.whatsAppMoveActive)
            .append(", to official: ").append(settings.whatsAppMoveToOfficial).append('\n')
        append("WhatsApp: ")
        append(
            when {
                !f.whatsAppInstalled -> "not installed"
                f.whatsAppDataKept -> "removed, data kept"
                isOurs(f) -> "ours"
                else -> "official"
            }
        )
        f.installedVersionName?.let { append(' ').append(it) }
        append('\n')
        f.manifest?.let { m ->
            append("Saved backup: ").append(m.state).append(", from ").append(m.waVersionName)
                .append(", ").append(m.treeFiles).append(" files, ")
                .append(m.treeBytes / (1024 * 1024)).append(" MB, ").append(m.crypt).append('\n')
        }
        f.record?.let { r ->
            append("Record: saved at ").append(r.savedAtMs)
            r.savedBackup()?.let { append(", ").append(it.size / (1024 * 1024)).append(" MB") }
            append(", ").append(r.crypt)
                .append(", restored at ").append(r.restoredAtMs).append(" from ").append(r.restoredFrom)
                .append(", confirmed at ").append(r.chatsConfirmedAtMs)
                .append(", proof at ").append(r.evidenceAtMs).append(", ")
                .append(r.evidenceSize / (1024 * 1024)).append(" MB\n")
        }
        if (f.recordUnreadable) append("Record: unreadable\n")
        append("Saved copy: ")
            .append(if (!f.savedPresent) "none" else if (f.savedMatches) "ok" else "mismatched")
            .append(if (f.savedTmpPresent) ", unfinished copy present" else "")
            .append('\n')
        append("Earlier copies: ").append(migration.earlierCopyCount()).append('\n')
        when (val problem = saveProblem) {
            is SaveProblem.NoRoom ->
                append("Last save: no room, needs ").append(problem.neededBytes / (1024 * 1024)).append(" MB\n")
            is SaveProblem.Failed ->
                append("Last save: failed: ").append(problem.details.joinToString(" / ")).append('\n')
            null -> Unit
        }
        append("Seen installed after Start: ").append(settings.whatsAppMoveSeenInstalledAt).append('\n')
        status.blockers.forEach { append("Blocker: ").append(it).append('\n') }
        status.warnings.forEach { append("Warning: ").append(it).append('\n') }
    }

    private fun describe(guide: MoveGuide): String {
        val phase = when (val phase = guide.phase) {
            is MovePhase.AtStep ->
                "step ${guide.direction.numberOf(phase.step)} (${phase.step.title(guide.direction)}), ${phase.variant}"
            MovePhase.Attention -> "needs attention"
            MovePhase.RemovedBeforeSaved -> "WhatsApp removed before its backup was saved"
            MovePhase.NothingToDo -> "nothing to do"
            is MovePhase.NotPossibleYet -> "not possible yet (${phase.installed} > ${phase.newest})"
        }
        return if (guide.finished != null) "$phase, finished" else phase
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
