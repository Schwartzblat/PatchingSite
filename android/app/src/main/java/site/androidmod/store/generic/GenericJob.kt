package site.androidmod.store.generic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import site.androidmod.patch.ApkSigner
import site.androidmod.patch.CantPatch
import site.androidmod.patch.GenericPatchEngine
import site.androidmod.patch.GenericPatchRequest
import site.androidmod.patch.GenericPatchResult
import site.androidmod.patch.GenericSource
import site.androidmod.patch.ManifestPatcher
import site.androidmod.patch.OfficialApk
import site.androidmod.patch.PatchBundle
import site.androidmod.patch.PatchException
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.NotEnoughSpaceException
import site.androidmod.store.data.isAuth
import site.androidmod.store.data.isOutOfSpace
import site.androidmod.store.data.isTransient
import site.androidmod.store.data.needsSubscription
import site.androidmod.store.patch.ArchiveInfo
import site.androidmod.store.patch.PATCH_MIN_SDK
import site.androidmod.store.patch.PatchFailure
import site.androidmod.store.patch.PatchStage
import site.androidmod.store.patch.PatchState
import site.androidmod.store.patch.SigningKey
import site.androidmod.store.patch.StoreUpdateRequired
import site.androidmod.store.ui.formatBytes
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Our server's part of a generic build (production: GenericApi, BundleCache and SigningKeyStore). */
interface GenericServer {
    /** @throws StoreUpdateRequired on 426; [ApiException] otherwise. */
    suspend fun latest(): GenericLatest

    /** The verified generic bundle; @throws PatchException.BadBundle when it does not verify. */
    suspend fun bundle(patcherVersion: String): PatchBundle

    /** A freshly minted `generic` paywall token. Secret. */
    suspend fun appToken(): GenericToken

    suspend fun signingKey(): SigningKey
}

enum class GenericFailure {
    SIGNED_OUT, ANDROID_TOO_OLD, NEEDS_SUBSCRIPTION, NOT_AVAILABLE, STORE_UPDATE_REQUIRED,
    NOT_ENOUGH_SPACE, NETWORK, CHANGED, NOT_OUR_COPY, FAILED,
}

/** What the user is told. [label] is the app's name; [bytesNeeded] is NOT_ENOUGH_SPACE's shortfall, -1 when unknown. */
fun GenericFailure.message(label: String, bytesNeeded: Long = -1): String = when (this) {
    GenericFailure.SIGNED_OUT -> "Sign in again to continue."
    GenericFailure.ANDROID_TOO_OLD -> "Patching apps needs Android 13 or newer."
    GenericFailure.NEEDS_SUBSCRIPTION -> "You need an active plan to patch apps."
    GenericFailure.NOT_AVAILABLE -> "Patching other apps isn't available right now. Try again later."
    GenericFailure.STORE_UPDATE_REQUIRED -> "Update the store to patch apps."
    GenericFailure.NOT_ENOUGH_SPACE ->
        "Free up ${if (bytesNeeded > 0) formatBytes(bytesNeeded) else "some space"} and try again."
    GenericFailure.NETWORK -> "Network error. Check your connection and try again."
    GenericFailure.CHANGED -> "$label changed since you picked it. Go back and pick it again."
    GenericFailure.NOT_OUR_COPY -> "The installed $label isn't our patched copy, so it can't be refreshed."
    GenericFailure.FAILED -> "Patching failed. Nothing was removed. Try again, or send us a report."
}

/** The failure PatchRegistry carries for the screens; the message stays the generic one. */
val GenericFailure.patchFailure: PatchFailure
    get() = when (this) {
        GenericFailure.SIGNED_OUT -> PatchFailure.SIGNED_OUT
        GenericFailure.ANDROID_TOO_OLD -> PatchFailure.ANDROID_TOO_OLD
        GenericFailure.NEEDS_SUBSCRIPTION -> PatchFailure.NEEDS_SUBSCRIPTION
        GenericFailure.NOT_AVAILABLE -> PatchFailure.NOT_SUPPORTED_YET
        GenericFailure.STORE_UPDATE_REQUIRED -> PatchFailure.STORE_UPDATE_REQUIRED
        GenericFailure.NOT_ENOUGH_SPACE -> PatchFailure.NOT_ENOUGH_SPACE
        GenericFailure.NETWORK -> PatchFailure.NETWORK
        GenericFailure.CHANGED, GenericFailure.NOT_OUR_COPY, GenericFailure.FAILED -> PatchFailure.FAILED
    }

sealed interface GenericOutcome {
    /** The verified build is in `builtDir`, and the record lists it. */
    data object Built : GenericOutcome

    /**
     * [message] is [GenericFailure.message]; [bytesNeeded] comes with NOT_ENOUGH_SPACE and
     * [minStoreVersionCode] with STORE_UPDATE_REQUIRED. [cause] is the exception the failure was
     * mapped from, for the log; it is not part of equality. Engine and HTTP messages carry no secrets.
     */
    class Failed(
        val reason: GenericFailure,
        val message: String,
        val bytesNeeded: Long = -1,
        val minStoreVersionCode: Long? = null,
        val cause: Throwable? = null,
    ) : GenericOutcome {
        override fun equals(other: Any?) = other is Failed && reason == other.reason && message == other.message &&
            bytesNeeded == other.bytesNeeded && minStoreVersionCode == other.minStoreVersionCode

        override fun hashCode() = listOf(reason, message, bytesNeeded, minStoreVersionCode).hashCode()

        override fun toString() =
            "Failed(reason=$reason, message=$message, bytesNeeded=$bytesNeeded, minStoreVersionCode=$minStoreVersionCode)"
    }

    /** The engine refused the app. A saved original stays until the run screen's Stop. */
    data class Unpatchable(val reasons: List<CantPatch>) : GenericOutcome
}

/** The words for an app the engine refused. */
fun unpatchableMessage(label: String, reasons: List<CantPatch>): String =
    "$label can't be patched: ${reasons.joinToString(" ") { cantPatchText(it) }} Nothing was removed."

/**
 * What a split APK says about itself: PackageManager's archive parser refuses a split on its own
 * ("Expected base APK, but found split"), so the job's inspector reads a split output here. Its
 * manifest's package and versionCode, and apksig's signers (the engine has verified each output's
 * signature already). Null for a base or a file that isn't an APK.
 */
fun splitArchiveInfo(file: File): ArchiveInfo? = try {
    val manifest = ZipFile(file).use { zip ->
        zip.getEntry(MANIFEST)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
    }
    val facts = manifest?.let(ManifestPatcher::facts)
    val versionCode = facts?.versionCode
    if (facts?.split == null || versionCode == null) null
    else ArchiveInfo(facts.packageName, versionCode, OfficialApk.signerCertSha256s(file))
} catch (error: Exception) {
    null
}

private const val MANIFEST = "AndroidManifest.xml"

/** What PatchService publishes for [outcome]: a build is Ready at [builtDir]. */
fun genericPatchState(outcome: GenericOutcome, label: String, builtDir: File, versionName: String): PatchState = when (outcome) {
    GenericOutcome.Built -> PatchState.Ready(builtDir, versionName)
    is GenericOutcome.Failed -> PatchState.Failed(outcome.reason.patchFailure, outcome.message)
    is GenericOutcome.Unpatchable -> PatchState.Failed(PatchFailure.FAILED, unpatchableMessage(label, outcome.reasons))
}

/**
 * Saves an installed app's original, then builds its generic patch, for the record's intent:
 * PATCH, or REFRESH of our patched copy. It never removes or installs anything; the run screen
 * does that.
 *
 * Every failure stops the run and keeps only what is verified: the record's `original` once every
 * copy matches its installed file, and its `built` once every output checks out and sits in
 * `builtDir`. [workDir] and `builtTmpDir` are emptied when a run starts and when it ends, however
 * it ends; a coroutine cancellation is rethrown after that cleanup. [reserve] returns the
 * shortfall, 0 when the bytes fit.
 */
class GenericJob(
    private val server: GenericServer,
    private val records: GenericRecords,
    private val reserve: (dir: File, bytes: Long) -> Long,
    private val engine: (GenericPatchRequest, ApkSigner) -> GenericPatchResult,
    private val signerFor: (SigningKey) -> ApkSigner,
    private val inspector: (File) -> ArchiveInfo?,
    private val installed: (String) -> InstalledPackage?,
    private val storePackages: () -> Set<String>,
    private val signedIn: () -> Boolean,
    private val sdkInt: Int,
    private val deviceAbis: List<String>,
    private val storeVersionCode: Long,
    private val workDir: File,
) {

    /** [onStage] may be called from a background thread (saving the original, signing). */
    suspend fun run(packageName: String, onStage: (PatchStage) -> Unit): GenericOutcome {
        val originalDir = records.originalDir(packageName)
        val builtTmpDir = records.builtTmpDir(packageName)
        var label = packageName
        // While true, original/ holds copies the record does not list: they are deleted, never kept.
        var unrecordedCopies = false
        try {
            clearTemporary(builtTmpDir)
            if (!signedIn()) return failed(GenericFailure.SIGNED_OUT, label)
            if (sdkInt < PATCH_MIN_SDK) return failed(GenericFailure.ANDROID_TOO_OLD, label)
            var record = blocking { records.get(packageName) } ?: return failed(GenericFailure.FAILED, label)
            label = record.label
            val intent = record.intent
            if (intent != GenericIntent.PATCH && intent != GenericIntent.REFRESH) return failed(GenericFailure.FAILED, label)

            // Still the app the record describes? No network until this holds for a PATCH.
            val app = blocking { installed(packageName) }
            val fromSaved = record.original.isNotEmpty()
            var key: SigningKey? = null
            if (intent == GenericIntent.PATCH) {
                // With the original saved, the app may already be gone: build from the copy.
                val gone = fromSaved && app == null
                if (!gone && (app == null || !isOriginal(app, record))) return failed(GenericFailure.CHANGED, label)
            } else {
                if (app == null || app.versionCode != record.versionCode) return failed(GenericFailure.CHANGED, label)
                key = server.signingKey()
                if (app.signerSha256s.lowercased() != setOf(key.certSha256.lowercase())) {
                    return failed(GenericFailure.NOT_OUR_COPY, label)
                }
            }
            if (fromSaved && !blocking { savedCopiesIntact(originalDir, record.original) }) {
                return failed(GenericFailure.FAILED, label)
            }

            // The engine reads the saved original, or for a Refresh without one, the installed patched copy.
            val installedApks = app?.apks.orEmpty()
            val saveFirst = intent == GenericIntent.PATCH && !fromSaved
            val (inputs, source) = when {
                saveFirst -> installedApks.map { File(originalDir, it.name) } to GenericSource.ORIGINAL
                fromSaved -> record.original.map { File(originalDir, it.name) } to GenericSource.ORIGINAL
                else -> installedApks to GenericSource.PATCHED
            }

            onStage(PatchStage.CheckingVersion)
            val latest = try {
                server.latest()
            } catch (error: ApiException) {
                if (error.status == HTTP_NOT_FOUND) return failed(GenericFailure.NOT_AVAILABLE, label, cause = error)
                throw error
            }
            val bundle = server.bundle(latest.patcherVersion)
            if (!bundle.isGeneric) return failed(GenericFailure.FAILED, label)
            if (bundle.meta.minSdk > sdkInt) return failed(GenericFailure.ANDROID_TOO_OLD, label)
            if (bundle.meta.minStoreVersionCode > storeVersionCode) {
                return failed(GenericFailure.STORE_UPDATE_REQUIRED, label, minStoreVersionCode = bundle.meta.minStoreVersionCode)
            }

            // Room for the copy (the same size as the installed files) and for the build.
            val shortfall = blocking {
                val copyBytes = if (saveFirst) installedApks.sumOf { it.length() } else 0L
                reserve(originalDir, copyBytes + GenericPatchEngine.bytesNeeded(if (saveFirst) installedApks else inputs, bundle))
            }
            if (shortfall > 0) return failed(GenericFailure.NOT_ENOUGH_SPACE, label, bytesNeeded = shortfall)

            if (saveFirst) {
                unrecordedCopies = true
                val saved = saveOriginal(installedApks, originalDir, onStage) ?: return failed(GenericFailure.FAILED, label)
                record = blocking { update(packageName, intent) { it.copy(original = saved) } }
                unrecordedCopies = false
            }

            // Fresh for every run.
            val token = server.appToken()
            val signingKey = key ?: server.signingKey()

            onStage(PatchStage.Patching)
            blocking { recreate(builtTmpDir) }
            val signer = signerFor(signingKey)
            val announcing = ApkSigner { unsigned, signed ->
                onStage(PatchStage.Signing)
                signer.sign(unsigned, signed)
            }
            val request = GenericPatchRequest(
                apks = inputs,
                source = source,
                bundle = bundle,
                paywallToken = token.token,
                signerCertSha256 = signingKey.certSha256,
                deviceSdk = sdkInt,
                deviceAbis = deviceAbis,
                workDir = workDir,
                outputDir = builtTmpDir,
                refusedPackages = storePackages(),
            )
            val result = try {
                blocking { engine(request, announcing) }
            } catch (error: PatchException.Unpatchable) {
                currentCoroutineContext().ensureActive()
                return GenericOutcome.Unpatchable(error.reasons)
            }

            // One output per input, each this app at the recorded version, signed only by the user's key.
            val outputs = result.apks
            val inPlace = outputs.map { it.name } == inputs.map { it.name } &&
                outputs.all { it.absoluteFile.parentFile == builtTmpDir.absoluteFile }
            if (!inPlace) return failed(GenericFailure.FAILED, label)
            for (output in outputs) {
                val info = blocking { inspector(output) }
                val checksOut = info != null && info.packageName == packageName && info.versionCode == record.versionCode &&
                    info.signerSha256s.lowercased() == setOf(signingKey.certSha256.lowercase())
                if (!checksOut) return failed(GenericFailure.FAILED, label)
            }

            blocking {
                val built = outputs.map { output ->
                    sync(output)
                    SavedApk(output.name, output.length(), sha256(output))
                }
                // The record never lists a build that isn't in built/: forget the old one before replacing it.
                if (record.built.isNotEmpty()) {
                    update(packageName, intent) { it.copy(built = emptyList(), builtBundleId = null, builtTokenExpiresAt = null) }
                }
                replaceDir(builtTmpDir, records.builtDir(packageName))
                update(packageName, intent) {
                    it.copy(built = built, builtBundleId = bundle.id, builtTokenExpiresAt = token.expiresAtMillis)
                }
            }
            return GenericOutcome.Built
        } catch (error: CancellationException) {
            throw error
        } catch (stop: Stop) {
            return failed(stop.reason, label)
        } catch (error: Exception) {
            return failureFor(error, label)
        } finally {
            clearTemporary(builtTmpDir)
            if (unrecordedCopies) originalDir.deleteRecursively()
        }
    }

    /**
     * Copies [apks] into [dir], then compares each copy's SHA-256 with its installed file's. Returns
     * the copies as saved, or null, with every copy deleted, when one does not match.
     */
    private suspend fun saveOriginal(apks: List<File>, dir: File, onStage: (PatchStage) -> Unit): List<SavedApk>? = blocking {
        // Whatever is there is unrecorded, from a run that was stopped.
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("Cannot create $dir")
        val total = apks.sumOf { it.length() }
        var done = 0L
        onStage(PatchStage.SavingOriginal(0, total))
        val saved = ArrayList<SavedApk>()
        for (apk in apks) {
            val copy = File(dir, apk.name)
            apk.inputStream().use { input ->
                FileOutputStream(copy).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        checkInterrupted()
                        onStage(PatchStage.SavingOriginal(done, total))
                    }
                    output.fd.sync()
                }
            }
            val sha256 = sha256(copy)
            if (sha256 != sha256(apk)) {
                dir.deleteRecursively()
                return@blocking null
            }
            saved += SavedApk(apk.name, copy.length(), sha256)
        }
        saved
    }

    /** A failure from a server call, the bundle, the engine or the disk. */
    private suspend fun failureFor(error: Exception, label: String): GenericOutcome.Failed = when (error) {
        is StoreUpdateRequired -> failed(
            GenericFailure.STORE_UPDATE_REQUIRED, label, minStoreVersionCode = error.minStoreVersionCode, cause = error,
        )
        is NotEnoughSpaceException -> failed(GenericFailure.NOT_ENOUGH_SPACE, label, error.bytesNeeded, cause = error)
        is ApiException -> failed(
            when {
                error.isAuth -> GenericFailure.SIGNED_OUT
                error.needsSubscription -> GenericFailure.NEEDS_SUBSCRIPTION
                isGenericOff(error) -> GenericFailure.NOT_AVAILABLE
                error.isTransient -> GenericFailure.NETWORK
                else -> GenericFailure.FAILED
            },
            label,
            cause = error,
        )
        is PatchException.Storage -> failed(GenericFailure.NOT_ENOUGH_SPACE, label, cause = error)
        is IOException ->
            failed(if (isOutOfSpace(error)) GenericFailure.NOT_ENOUGH_SPACE else GenericFailure.FAILED, label, cause = error)
        else -> failed(GenericFailure.FAILED, label, cause = error)
    }

    /** A failure, unless the job was cancelled meanwhile: that is rethrown instead. */
    private suspend fun failed(
        reason: GenericFailure,
        label: String,
        bytesNeeded: Long = -1,
        minStoreVersionCode: Long? = null,
        cause: Throwable? = null,
    ): GenericOutcome.Failed {
        currentCoroutineContext().ensureActive()
        return GenericOutcome.Failed(reason, reason.message(label, bytesNeeded), bytesNeeded, minStoreVersionCode, cause)
    }

    /** Applies [change] to the record on disk, which must still be this flow's: one stopped meanwhile is not brought back. */
    private fun update(packageName: String, intent: GenericIntent, change: (GenericRecord) -> GenericRecord): GenericRecord {
        val current = records.get(packageName)?.takeIf { it.intent == intent } ?: throw Stop(GenericFailure.FAILED)
        return change(current).also(records::put)
    }

    private fun isOriginal(app: InstalledPackage, record: GenericRecord): Boolean =
        app.versionCode == record.versionCode && app.signerSha256s == record.originalSigners &&
            app.apks.map { it.name } == record.apkNames

    private fun savedCopiesIntact(dir: File, saved: List<SavedApk>): Boolean = saved.all { apk ->
        val file = File(dir, apk.name)
        file.isFile && file.length() == apk.size && sha256(file) == apk.sha256
    }

    /** Runs blocking work off the caller's thread; cancelling the job interrupts it. */
    private suspend fun <T> blocking(block: () -> T): T = runInterruptible(Dispatchers.IO, block)

    private fun clearTemporary(builtTmpDir: File) {
        workDir.walkBottomUp().filter { it != workDir }.forEach { it.delete() }
        builtTmpDir.deleteRecursively()
    }

    private fun recreate(dir: File) {
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("Cannot create $dir")
    }

    /** Puts [source] at [target] by one rename, so `built/` is never half a build. */
    private fun replaceDir(source: File, target: File) {
        if (target.exists() && !target.deleteRecursively()) throw IOException("Cannot remove $target")
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /** Puts [file]'s bytes on disk before a record points at it. */
    private fun sync(file: File) = FileOutputStream(file, true).use { it.fd.sync() }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                checkInterrupted()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Stops a long copy or hash between chunks; runInterruptible turns the interrupt into the cancellation. */
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private fun Set<String>.lowercased(): Set<String> = mapTo(HashSet()) { it.lowercase() }

    /** A failure already classified where it happened. */
    private class Stop(val reason: GenericFailure) : Exception()

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val BUFFER_BYTES = 256 * 1024

        /** The server's words while generic patching is switched off, or its bundle can't be served. */
        val OFF_MESSAGES = setOf("Generic patching is off", "Generic bundle unavailable")

        fun isGenericOff(error: ApiException) = (error.status == 404 || error.status == 503) && error.message in OFF_MESSAGES
    }
}
