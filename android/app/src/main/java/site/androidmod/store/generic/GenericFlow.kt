package site.androidmod.store.generic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import site.androidmod.patch.GenericPatchInfo
import site.androidmod.store.install.InstallFailure
import site.androidmod.store.install.InstallOutcome
import java.io.File
import java.io.IOException

/**
 * Drives an app's generic swap after [GenericJob] has saved its original and built its patched
 * copy: removing, installing, stopping, putting the original back and refreshing. The run screen
 * and the Library call it. Each call decides from the record and from what is installed at that
 * moment ([genericPhase]), never from what a screen last showed.
 *
 * The customer keeps a working app at every step:
 * - nothing is removed ([askedToUninstall] says go) unless the copy that replaces it is on disk
 *   and still matches its recorded SHA-256;
 * - every install re-hashes the files it hands to Android;
 * - nothing changes while the package's job runs ([isBusy]) or while one of its installs is in
 *   progress here.
 *
 * Every function except the two installs blocks (it reads PackageManager and hashes files), so
 * call it off the main thread. The installs switch to IO themselves.
 */
class GenericFlow(
    private val records: GenericRecords,
    private val installedState: (String) -> InstalledState?,
    private val userCert: () -> String?,
    private val install: suspend (apks: List<File>, extraTrusted: Set<String>) -> InstallOutcome,
    private val sha256: (File) -> String,
    private val now: () -> Long,
    private val isBusy: (String) -> Boolean,
) {
    // Guards every read-then-write of a record, and [installing].
    private val lock = Any()

    /** Packages with an install of ours in progress. */
    private val installing = HashSet<String>()

    /**
     * Starts patching [app] with a PATCH record, which the job then fills. Refused
     * (IllegalStateException) while a flow is under way for the app, and when it would replace a
     * saved original whose signers [app] doesn't have (our patched copy, say). Otherwise a
     * finished record goes, with its files: the original it saved is installed again.
     */
    fun begin(app: InstalledPackage): GenericRecord = synchronized(lock) {
        val existing = records.get(app.packageName)
        check(existing?.intent == null) { "${app.label} is already being patched or put back." }
        check(existing == null || existing.original.isEmpty() || existing.originalSigners == app.signerSha256s) {
            "${app.label}'s saved original is still needed."
        }
        val record = GenericRecord(
            packageName = app.packageName,
            label = app.label,
            versionName = app.versionName,
            versionCode = app.versionCode,
            originalSigners = app.signerSha256s,
            apkNames = app.apks.map { it.name },
            intent = GenericIntent.PATCH,
        )
        if (existing != null) records.remove(app.packageName)
        records.put(record)
        record
    }

    /**
     * Records our patch of [app] when it has no record (the store's data was cleared, say): no
     * intent, no saved original, the bundle its patch-info names, and no known token expiry.
     * Refused (IllegalStateException) when the app has a record.
     */
    fun adopt(app: InstalledPackage, info: GenericPatchInfo): GenericRecord = synchronized(lock) {
        check(records.get(app.packageName) == null) { "${app.label} already has a record." }
        GenericRecord(
            packageName = app.packageName,
            label = app.label,
            versionName = app.versionName,
            versionCode = app.versionCode,
            originalSigners = emptySet(),
            apkNames = app.apks.map { it.name },
            patchedBundleId = info.bundleId,
        ).also(records::put)
    }

    /** The step [packageName] is at now; null when it has no record. */
    fun phase(packageName: String): GenericPhase? = records.get(packageName)?.let(::phaseOf)

    /**
     * Call right before the UI asks Android to remove the app (ACTION_DELETE), and fire that only
     * on null: the request time is then on disk. Anything else says why not, and nothing was
     * written. Allowed at RemoveOriginal while the saved original and the patched copy still match
     * their hashes, and at RemovePatched while the saved original does.
     */
    fun askedToUninstall(packageName: String): String? = synchronized(lock) {
        val record = records.get(packageName) ?: return@synchronized notReadyToRemove(packageName)
        if (busy(packageName)) return@synchronized BUSY
        val intact = when (phaseOf(record)) {
            is GenericPhase.RemoveOriginal ->
                intact(records.originalDir(packageName), record.original) && intact(records.builtDir(packageName), record.built)
            is GenericPhase.RemovePatched -> intact(records.originalDir(packageName), record.original)
            else -> return@synchronized notReadyToRemove(record.label)
        }
        when {
            !intact && record.intent == GenericIntent.RESTORE -> DAMAGED_ORIGINAL
            !intact -> "The saved copies of ${record.label} are damaged, so it wasn't removed. Stop, then patch it again."
            else -> {
                records.put(record.copy(uninstallAskedAt = now()))
                null
            }
        }
    }

    /** Installs our patched copy, base first: at InstallPatched, or InstallUpdate for a Refresh. A failure changes nothing. */
    suspend fun installPatched(packageName: String): InstallOutcome = installStep(packageName) { record, phase ->
        val dir = records.builtDir(packageName)
        when {
            phase != GenericPhase.InstallPatched && phase != GenericPhase.InstallUpdate -> Step.Refused(atAnotherStep(record.label))
            !intact(dir, record.built) -> Step.Refused("The patched copy of ${record.label} is damaged, so it wasn't installed.")
            // Signed with the user's key, which the installer trusts already.
            else -> Step.Ready(record.built.map { File(dir, it.name) }, emptySet())
        }
    }

    /** Installs the saved original, base first, trusting its own signers for this install only: at InstallOriginal only. */
    suspend fun installOriginal(packageName: String): InstallOutcome = installStep(packageName) { record, phase ->
        val dir = records.originalDir(packageName)
        when {
            phase != GenericPhase.InstallOriginal -> Step.Refused(atAnotherStep(record.label))
            !intact(dir, record.original) -> Step.Refused(DAMAGED_ORIGINAL)
            else -> Step.Ready(record.original.map { File(dir, it.name) }, record.originalSigners)
        }
    }

    /**
     * Completes a flow the phone shows done. At Patched with an intent, the installed build becomes
     * the patch (its bundle, token expiry and time) and built/ is deleted. At Restored the app is
     * forgotten, saved original included. Any other step changes nothing.
     */
    fun finish(packageName: String) {
        synchronized(lock) {
            val record = records.get(packageName) ?: return
            if (busy(packageName)) return
            when (phaseOf(record)) {
                GenericPhase.Patched -> if (record.intent != null) {
                    records.put(
                        record.withoutBuild().copy(
                            intent = null,
                            uninstallAskedAt = null,
                            patchedBundleId = record.builtBundleId,
                            tokenExpiresAt = record.builtTokenExpiresAt,
                            patchedAt = now(),
                        ),
                    )
                    // Once the record no longer lists it, so a record never lists a build that isn't there.
                    records.builtDir(packageName).deleteRecursively()
                }
                GenericPhase.Restored -> records.remove(packageName)
                else -> Unit
            }
        }
    }

    /**
     * Abandons the flow under way, only where the customer keeps a working app, and says whether it
     * did:
     * - PATCH: while the original is installed (its signers, read now), at whatever step; the record
     *   and all its files go. Never once it is removed: then the way on is installing the patched
     *   copy or putting the original back.
     * - REFRESH: the new build goes; the patched copy installed stays.
     * - RESTORE: only before the patched copy is removed.
     */
    fun stop(packageName: String): Boolean = synchronized(lock) {
        val record = records.get(packageName) ?: return@synchronized false
        if (busy(packageName)) return@synchronized false
        when (record.intent) {
            GenericIntent.PATCH -> {
                val installed = installedState(packageName)
                val originalInstalled = installed != null && record.originalSigners.isNotEmpty() &&
                    installed.signers == record.originalSigners
                if (originalInstalled) records.remove(packageName)
                originalInstalled
            }
            GenericIntent.REFRESH -> {
                records.put(record.withoutBuild().copy(intent = null, uninstallAskedAt = null))
                records.builtDir(packageName).deleteRecursively()
                true
            }
            GenericIntent.RESTORE -> {
                val patchedInstalled = phaseOf(record) is GenericPhase.RemovePatched
                if (patchedInstalled) records.put(record.copy(intent = null, uninstallAskedAt = null))
                patchedInstalled
            }
            null -> false
        }
    }

    /**
     * Starts putting the original back (intent RESTORE), after every saved copy matches its hash
     * again. Null when started or already under way; otherwise why not, and nothing changed.
     */
    fun startRestore(packageName: String): String? = synchronized(lock) {
        val record = records.get(packageName) ?: return@synchronized noSavedOriginal(packageName)
        when {
            busy(packageName) -> BUSY
            record.intent == GenericIntent.RESTORE -> null
            record.original.isEmpty() -> noSavedOriginal(record.label)
            !intact(records.originalDir(packageName), record.original) -> DAMAGED_ORIGINAL
            else -> {
                records.put(record.copy(intent = GenericIntent.RESTORE, uninstallAskedAt = null))
                null
            }
        }
    }

    /** Starts a Refresh (intent REFRESH) of a finished patch whose copy is installed; true when started or already under way. */
    fun startRefresh(packageName: String): Boolean = synchronized(lock) {
        val record = records.get(packageName) ?: return@synchronized false
        when {
            busy(packageName) -> false
            record.intent == GenericIntent.REFRESH -> true
            record.intent != null || phaseOf(record) != GenericPhase.Patched -> false
            else -> {
                // Always a new build with a new token: nothing built before counts.
                records.put(record.withoutBuild().copy(intent = GenericIntent.REFRESH, uninstallAskedAt = null))
                records.builtDir(packageName).deleteRecursively()
                true
            }
        }
    }

    /** Deletes the saved original of a finished patch whose copy is installed; true when it did. */
    fun deleteSavedOriginal(packageName: String): Boolean = synchronized(lock) {
        val record = records.get(packageName) ?: return@synchronized false
        val allowed = !busy(packageName) && record.intent == null && record.original.isNotEmpty() &&
            phaseOf(record) == GenericPhase.Patched
        if (allowed) {
            records.put(record.copy(original = emptyList()))
            records.originalDir(packageName).deleteRecursively()
        }
        allowed
    }

    /** The saved original's size as recorded: what [deleteSavedOriginal] frees. */
    fun savedOriginalBytes(packageName: String): Long = records.get(packageName)?.original?.sumOf { it.size } ?: 0L

    private sealed interface Step {
        class Ready(val apks: List<File>, val extraTrusted: Set<String>) : Step
        class Refused(val message: String) : Step
    }

    /**
     * Installs what [choose] picks for the package's record and current step. Choosing, hashing
     * included, runs on IO under the lock; while Android installs, every other change to the
     * package is refused.
     */
    private suspend fun installStep(packageName: String, choose: (GenericRecord, GenericPhase) -> Step): InstallOutcome {
        var reserved = false
        try {
            val step = runInterruptible(Dispatchers.IO) {
                synchronized(lock) {
                    val record = records.get(packageName)
                    val step = when {
                        record == null -> Step.Refused(atAnotherStep(packageName))
                        busy(packageName) -> Step.Refused(BUSY)
                        else -> choose(record, phaseOf(record))
                    }
                    if (step is Step.Ready) {
                        installing += packageName
                        reserved = true
                    }
                    step
                }
            }
            return when (step) {
                is Step.Refused -> InstallOutcome.Failure(step.message, InstallFailure.UNKNOWN)
                is Step.Ready -> install(step.apks, step.extraTrusted)
            }
        } finally {
            synchronized(lock) { if (reserved) installing -= packageName }
        }
    }

    private fun phaseOf(record: GenericRecord): GenericPhase =
        genericPhase(record, installedState(record.packageName), userCert())

    /** Under [lock]. */
    private fun busy(packageName: String): Boolean = packageName in installing || isBusy(packageName)

    /** Every copy in [saved] is in [dir] with its recorded size and SHA-256; an empty list is not. */
    private fun intact(dir: File, saved: List<SavedApk>): Boolean = saved.isNotEmpty() && saved.all { apk ->
        val file = File(dir, apk.name)
        try {
            file.isFile && file.length() == apk.size && sha256(file) == apk.sha256
        } catch (error: IOException) {
            false
        }
    }

    private fun GenericRecord.withoutBuild() = copy(built = emptyList(), builtBundleId = null, builtTokenExpiresAt = null)

    private companion object {
        const val BUSY = "Wait for the current step to finish, then try again."
        const val DAMAGED_ORIGINAL = "The saved original is damaged, so it can't be put back."

        fun atAnotherStep(label: String) = "Nothing was installed, because $label is at another step now."
        fun notReadyToRemove(label: String) = "$label isn't ready to be removed."
        fun noSavedOriginal(label: String) = "There's no saved original of $label to put back."
    }
}
