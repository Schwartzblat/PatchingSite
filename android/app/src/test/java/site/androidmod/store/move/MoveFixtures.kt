package site.androidmod.store.move

import com.patcher.migrate.EngineState
import com.patcher.migrate.Facts
import com.patcher.migrate.MoveRecord
import com.patcher.migrate.ParkManifest
import com.patcher.migrate.Status

/** The store's own certificate in these tests: what ours is signed with. */
val OURS_SIG = "a".repeat(64)

/** The Play Store WhatsApp's certificate. */
val OFFICIAL_SIG = "b".repeat(64)

/** "Now" for every test, so backup ages are exact. */
const val NOW = 1_800_000_000_000L

/** The user tapped Start ten minutes ago. */
const val STARTED_AT = NOW - 10 * 60_000L

/** Made after Start and a minute old: fresh. */
const val FRESH_BACKUP = NOW - 60_000L

/** Made a minute before Start: stale, however recent. */
const val STALE_BACKUP = STARTED_AT - 60_000L

/** The newest build on the server in these tests. */
const val NEWEST = "2.26.39.74"

/** When the installed WhatsApp was first installed: long before any move. */
const val FIRST_INSTALL = NOW - 30L * 24 * 60 * 60 * 1000

/**
 * What the engine sees. The defaults are the plainest start of a move into ours:
 * file access granted, the official WhatsApp 2.26.39.70 installed, its folder
 * holding a fresh 1 MB crypt14 backup, nothing parked and nothing saved.
 * [installed] = false drops the version and signature too, as Migration.facts()
 * does. [record] adds the move's record and, unless [saved] says otherwise, a
 * Saved/ copy that matches it.
 */
fun facts(
    access: Boolean = true,
    installed: Boolean = true,
    signature: String? = OFFICIAL_SIG,
    versionName: String = "2.26.39.70",
    versionCode: Long = 263970L,
    waFolder: Boolean = true,
    parkFolder: Boolean = false,
    kept: Boolean = false,
    manifest: ParkManifest? = null,
    dataKept: Boolean = false,
    newestBackupMs: Long = FRESH_BACKUP,
    newestBackupCrypt: String = "crypt14",
    record: MoveRecord? = null,
    saved: Boolean = record?.hasSaved() == true,
    savedMatches: Boolean = saved,
    firstInstall: Long = FIRST_INSTALL,
    newestBackupSize: Long = 1_000_000L,
): Facts = Facts(
    access,
    installed,
    if (installed) versionName else null,
    if (installed) versionCode else 0L,
    if (installed) signature else null,
    waFolder,
    parkFolder,
    kept,
    manifest,
    "/sdcard/WAMigrate/Databases-kept",
    dataKept,
    newestBackupMs,
    newestBackupCrypt,
).withMove(
    record,
    false,
    saved,
    savedMatches,
    false,
    kept,
    if (installed) firstInstall else 0L,
    if (newestBackupMs > 0) newestBackupSize else 0L,
    if (newestBackupMs > 0) "msgstore.db.$newestBackupCrypt" else null,
)

/**
 * The record park() writes. Defaults: parked from the official WhatsApp
 * 2.26.39.70, holding one backup made five minutes ago.
 */
fun manifest(
    state: ParkManifest.State = ParkManifest.State.PARKED,
    from: String = OFFICIAL_SIG,
    versionName: String = "2.26.39.70",
    versionCode: Long = 263970L,
    crypt: String = "crypt14",
    backupMs: Long = NOW - 5 * 60_000L,
): ParkManifest = ParkManifest(
    state, NOW - 4 * 60_000L, versionName, versionCode, from, crypt, 120, 50L * 1024 * 1024,
    listOf(ParkManifest.Db("msgstore.db.$crypt", 1_000_000L, backupMs, "c".repeat(64))),
)

/**
 * The move's own record, written once its backup is saved. Defaults: leaving the
 * official WhatsApp 2.26.39.70, its fresh 1 MB backup saved, nothing put back.
 */
fun record(
    from: String = OFFICIAL_SIG,
    versionName: String = "2.26.39.70",
    versionCode: Long = 263970L,
    firstInstall: Long = FIRST_INSTALL,
    backupMs: Long = FRESH_BACKUP,
    backupSize: Long = 1_000_000L,
    restoredAt: Long = 0L,
    restoredFrom: MoveRecord.Source = MoveRecord.Source.NONE,
    confirmedAt: Long = 0L,
    evidenceAt: Long = 0L,
    evidenceSize: Long = 0L,
): MoveRecord = MoveRecord(
    versionName, versionCode, from, firstInstall, "crypt14", backupMs + 30_000L,
    listOf(ParkManifest.Db("Databases/msgstore.db.crypt14", backupSize, backupMs, "d".repeat(64))),
    restoredAt, restoredFrom, confirmedAt, evidenceAt, evidenceSize,
    if (evidenceAt > 0) "e".repeat(64) else "",
)

/** The engine's own decision for [f]. */
fun status(f: Facts): Status = EngineState.of(f)

/** Whether [f]'s WhatsApp is ours, compared the way MoveRepository.isOurs compares. */
fun oursIn(f: Facts): Boolean =
    f.whatsAppInstalled && !f.whatsAppDataKept && f.installedSignatureSha256 == OURS_SIG

/** moveGuide over [f], with the store's inputs defaulted the way the phone would give them. */
fun guide(
    f: Facts,
    moveActive: Boolean = false,
    toOfficialHint: Boolean = false,
    newest: String? = NEWEST,
    downloaded: Boolean = false,
    acks: MoveAcks = MoveAcks(),
    startedAt: Long = STARTED_AT,
    seenInstalledAt: Long = 0L,
): MoveGuide = moveGuide(
    status = status(f),
    ours = oursIn(f),
    // As MoveRepository.parkedFromOurs: the manifest's certificate, else the record's.
    parkedFromOurs = f.originalSignature() == OURS_SIG,
    moveActive = moveActive,
    toOfficialHint = toOfficialHint,
    newestVersion = newest,
    downloaded = downloaded,
    now = NOW,
    startedAt = startedAt,
    acks = acks,
    seenInstalledAt = seenInstalledAt,
)
