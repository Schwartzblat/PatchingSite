package site.androidmod.store.generic

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import site.androidmod.patch.GenericPatchInfo
import site.androidmod.store.data.ApiException
import site.androidmod.store.patch.StoreUpdateRequired

/** One generic patch as the Library shows it. */
data class GenericEntry(
    val record: GenericRecord,
    /**
     * The step it is at; null when that can't be told because the user's signing key couldn't be
     * had (without it our own copy would read as changed outside the store).
     */
    val phase: GenericPhase?,
    /** The saved original's recorded size; 0 when none is saved. */
    val savedOriginalBytes: Long,
    /** Why to Refresh; only for a finished patch (Patched, no flow under way). */
    val hint: RefreshHint?,
)

/**
 * The Library's generic entries, from the records on this phone. Each call first adopts our own
 * patches that have no record (the store's data was cleared, say): installed apps signed with the
 * user's key whose base carries a generic patch-info. That much is local. The one network call is
 * [GenericApi.latest], once per call and only when a record exists; it names no app.
 *
 * [userCert] is the user's certificate as lowercase SHA-256, cached or else fetched; null when it
 * can't be had. [listApps] and [readInfo] read PackageManager and the apps' files, and deriving each
 * phase hashes an installed base, so all of that runs on [io].
 */
class GenericLibrarySource(
    private val records: GenericRecords,
    private val flow: GenericFlow,
    private val api: GenericApi,
    private val listApps: () -> List<InstalledPackage>,
    private val readInfo: (File) -> GenericPatchInfo?,
    private val userCert: suspend () -> String?,
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun entries(): List<GenericEntry> {
        val (listed, keyKnown) = withContext(io) {
            // First, so the phases below can tell our copies apart (the flow reads the cached key).
            val cert = userCert()
            if (cert != null) adoptOrphans(cert)
            records.all() to (cert != null)
        }
        if (listed.isEmpty()) return emptyList()
        val latestBundleId = latestBundleId()
        // Interruptible: a superseded refresh stops hashing.
        return runInterruptible(io) {
            listed.mapNotNull { entry(it, latestBundleId, keyKnown) }
        }
    }

    private fun adoptOrphans(cert: String) {
        val recorded = records.all().mapTo(HashSet()) { it.packageName }
        val apps = try {
            listApps()
        } catch (error: RuntimeException) {
            // PackageManager failed: adopt on the next refresh, and still show the records.
            return
        }
        for (app in apps) {
            if (cert !in app.signerSha256s || app.packageName in recorded) continue
            val base = app.apks.firstOrNull() ?: continue
            val info = try {
                readInfo(base)
            } catch (error: Exception) {
                // An unreadable base, or a malformed patch-info: not one we can adopt.
                null
            } ?: continue
            try {
                flow.adopt(app, info)
            } catch (raced: IllegalStateException) {
                // Recorded meanwhile.
            }
        }
    }

    /** The newest generic bundle; null when it can't be read, a 426 included: that sets no hint. */
    private suspend fun latestBundleId(): String? = try {
        api.latest().bundleId
    } catch (error: CancellationException) {
        throw error
    } catch (error: ApiException) {
        null
    } catch (error: StoreUpdateRequired) {
        null
    }

    /** Null when the record went meanwhile. */
    private fun entry(record: GenericRecord, latestBundleId: String?, keyKnown: Boolean): GenericEntry? {
        val derived = flow.phase(record.packageName) ?: return null
        val phase = derived.takeUnless { it is GenericPhase.Attention && !keyKnown }
        val finished = phase == GenericPhase.Patched && record.intent == null
        return GenericEntry(
            record = record,
            phase = phase,
            savedOriginalBytes = record.original.sumOf { it.size },
            hint = if (finished) refreshHint(record, latestBundleId, now()) else null,
        )
    }
}
