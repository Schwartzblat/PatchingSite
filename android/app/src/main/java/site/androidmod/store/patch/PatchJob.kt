package site.androidmod.store.patch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import site.androidmod.patch.ApkSigner
import site.androidmod.patch.PatchBundle
import site.androidmod.patch.PatchException
import site.androidmod.patch.PatchRequest
import site.androidmod.patch.PatchResult
import site.androidmod.patch.VersionRecord
import site.androidmod.patch.featuresOff
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.NotEnoughSpaceException
import site.androidmod.store.data.isAuth
import site.androidmod.store.data.isOutOfSpace
import site.androidmod.store.data.isTransient
import site.androidmod.store.data.needsSubscription
import site.androidmod.store.ui.notEnoughSpaceMessage
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Why a build runs: the guide's DOWNLOAD step, or an update of the installed app. */
enum class PatchMode { GUIDE, UPDATE }

/** Where a build is, for the progress notification and the screens. */
sealed interface PatchStage {
    data object CheckingVersion : PatchStage

    /** [total] is -1 while the size is unknown. */
    data class Downloading(val bytesRead: Long, val total: Long) : PatchStage
    data object Patching : PatchStage
    data object Signing : PatchStage
}

enum class PatchFailure {
    SIGNED_OUT, ANDROID_TOO_OLD, NEEDS_SUBSCRIPTION, NOT_SUPPORTED_YET, STORE_UPDATE_REQUIRED,
    NOT_ENOUGH_SPACE, NETWORK, NOT_OFFICIAL, FAILED,
}

/** What the user is told. [bytesNeeded] is NOT_ENOUGH_SPACE's shortfall, -1 when unknown. */
fun PatchFailure.message(bytesNeeded: Long = -1): String = when (this) {
    PatchFailure.SIGNED_OUT -> "Sign in again to continue."
    PatchFailure.ANDROID_TOO_OLD -> "Patching on this phone needs Android 13 or newer."
    PatchFailure.NEEDS_SUBSCRIPTION -> "You need a subscription to get it."
    PatchFailure.NOT_SUPPORTED_YET -> "Not supported yet. This version of WhatsApp is new, so try again shortly."
    PatchFailure.STORE_UPDATE_REQUIRED -> "This app needs an update first. It's updating now."
    PatchFailure.NOT_ENOUGH_SPACE -> notEnoughSpaceMessage(NotEnoughSpaceException(bytesNeeded))
    PatchFailure.NETWORK -> "Network error. Check your connection and try again."
    PatchFailure.NOT_OFFICIAL -> "The download from whatsapp.com didn't check out. Try again."
    PatchFailure.FAILED -> "Patching failed. Nothing on your phone was changed."
}

sealed interface PatchOutcome {
    data class Ready(val file: File, val versionName: String, val versionCode: Long, val revision: Int) : PatchOutcome

    /**
     * [message] is [PatchFailure.message]; [minStoreVersionCode] comes with STORE_UPDATE_REQUIRED.
     * [cause] is the exception the failure was mapped from, for the log and for telling a 401 apart;
     * it is not part of equality. Engine and HTTP messages carry no secrets.
     */
    class Failed(
        val reason: PatchFailure,
        val message: String,
        val minStoreVersionCode: Long? = null,
        val cause: Throwable? = null,
    ) : PatchOutcome {
        override fun equals(other: Any?) = other is Failed && reason == other.reason &&
            message == other.message && minStoreVersionCode == other.minStoreVersionCode

        override fun hashCode() = listOf(reason, message, minStoreVersionCode).hashCode()

        override fun toString() = "Failed(reason=$reason, message=$message, minStoreVersionCode=$minStoreVersionCode)"
    }

    /** The user declined the features-off alert. */
    data object Cancelled : PatchOutcome
}

/** What an APK file says about itself (production: PackageManager.getPackageArchiveInfo). */
data class ArchiveInfo(val packageName: String, val versionCode: Long, val signerSha256s: Set<String>)

/** Our server's part of a build (production: PatchApi, BundleCache and SigningKeyStore). */
interface PatchServer {
    /** @throws StoreUpdateRequired on 426; [ApiException] otherwise (404: nothing supported yet). */
    suspend fun latest(pkg: String): LatestPatch

    /** The record JSON for `VersionRecord.parse`, or null for a build the server does not know. Exactly one hash. */
    suspend fun record(pkg: String, patcherVersion: String, apkSha256: String? = null, dexSha256: String? = null): String?

    /** A verified bundle; @throws PatchException.BadBundle when it does not verify. */
    suspend fun bundle(pkg: String, patcherVersion: String): PatchBundle

    /** The paywall token. Secret. */
    suspend fun appToken(pkg: String): String

    suspend fun signingKey(): SigningKey
}

/** Fetches the official APK from its publisher (production: ApiClient, which sends that host no account headers). */
interface OfficialDownloader {
    /** Content-Length from a HEAD request, or null when the host sends none. */
    suspend fun size(url: String): Long?

    /**
     * Replaces [target] with the file at [url], feeding [digest] every byte kept (reset whenever
     * the transfer starts over). @throws NotEnoughSpaceException, ApiException, IOException.
     */
    suspend fun download(url: String, target: File, digest: MessageDigest?, onProgress: (bytesRead: Long, total: Long) -> Unit)
}

/**
 * One on-device build of the latest supported version of a package, from the official APK to a
 * patched APK signed with the account's key, handed over at `finalFile(versionName)`.
 *
 * [workDir] (`filesDir/patch/work`) belongs to the job: it is emptied when a run starts and when
 * it ends, however it ends. A coroutine cancellation is rethrown after that cleanup, never
 * reported as [PatchOutcome.Failed]. [reserve] returns the shortfall, 0 when the bytes fit.
 */
class PatchJob(
    private val server: PatchServer,
    private val downloader: OfficialDownloader,
    private val reserve: (dir: File, bytes: Long) -> Long,
    private val verifyOfficial: (apk: File, allowedCertSha256: List<String>) -> Unit,
    private val dexHash: (apk: File) -> String,
    private val patcher: (PatchRequest, ApkSigner) -> PatchResult,
    private val signerFor: (SigningKey) -> ApkSigner,
    private val inspector: (File) -> ArchiveInfo?,
    private val signedIn: () -> Boolean,
    private val sdkInt: Int,
    private val storeVersionCode: Long,
    private val workDir: File,
) {

    /**
     * [onStage] may be called from a background thread (download progress, signing). [confirm]
     * runs only when visible features are off; false ends the run as [PatchOutcome.Cancelled].
     */
    suspend fun run(
        pkg: String,
        finalFile: (versionName: String) -> File,
        onStage: (PatchStage) -> Unit,
        confirm: suspend (featureTitles: List<String>) -> Boolean,
    ): PatchOutcome {
        var handedOver: File? = null
        try {
            clearWorkDir(keep = null)
            workDir.mkdirs()
            if (!signedIn()) return failed(PatchFailure.SIGNED_OUT)
            if (sdkInt < MIN_SDK) return failed(PatchFailure.ANDROID_TOO_OLD)

            onStage(PatchStage.CheckingVersion)
            val latest = try {
                server.latest(pkg)
            } catch (error: ApiException) {
                if (error.status == HTTP_NOT_FOUND) return failed(PatchFailure.NOT_SUPPORTED_YET)
                throw error
            }
            val bundle = server.bundle(pkg, latest.patcherVersion)
            if (bundle.meta.minSdk > sdkInt) return failed(PatchFailure.ANDROID_TOO_OLD)
            if (bundle.meta.minStoreVersionCode > storeVersionCode) {
                return failed(PatchFailure.STORE_UPDATE_REQUIRED, minStoreVersionCode = bundle.meta.minStoreVersionCode)
            }

            val official = File(workDir, OFFICIAL_APK)
            val apkSha256 = downloadOfficial(latest.apkUrl, official, onStage)
            blocking { verifyOfficial(official, bundle.features.officialCertSha256) }

            // By the file's own hashes, not latest's: whatsapp.com may have moved on meanwhile.
            val recordJson = server.record(pkg, latest.patcherVersion, apkSha256 = apkSha256)
                ?: server.record(pkg, latest.patcherVersion, dexSha256 = blocking { dexHash(official) })
                ?: return failed(PatchFailure.NOT_SUPPORTED_YET)
            val record = VersionRecord.parse(recordJson)
            if (!record.supported) return failed(PatchFailure.NOT_SUPPORTED_YET)
            val revision = revisionOf(recordJson) ?: latest.revision

            val titles = bundle.features.visibleTitles(featuresOff(bundle.features, record))
            if (titles.isNotEmpty() && !confirm(titles)) return PatchOutcome.Cancelled

            val token = server.appToken(pkg)
            val key = server.signingKey()

            onStage(PatchStage.Patching)
            val signed = File(workDir, SIGNED_APK)
            val signer = signerFor(key)
            val announcing = ApkSigner { unsigned, output ->
                onStage(PatchStage.Signing)
                signer.sign(unsigned, output)
            }
            val request = PatchRequest(
                official = official,
                bundle = bundle,
                record = record,
                paywallToken = token,
                revision = revision,
                workDir = workDir,
                output = signed,
            )
            blocking { patcher(request, announcing) }

            val info = blocking { inspector(signed) }
            val checksOut = info != null && info.packageName == pkg && info.versionCode == record.versionCode &&
                info.signerSha256s.any { it.equals(key.certSha256, ignoreCase = true) }
            if (!checksOut) return failed(PatchFailure.FAILED)

            val target = finalFile(record.versionName)
            blocking { moveInto(signed, target) }
            handedOver = target
            return PatchOutcome.Ready(target, record.versionName, record.versionCode, revision)
        } catch (error: CancellationException) {
            throw error
        } catch (stop: Stop) {
            return failed(stop.reason, stop.bytesNeeded, cause = stop.cause)
        } catch (error: Exception) {
            return failureFor(error)
        } finally {
            clearWorkDir(keep = handedOver)
        }
    }

    /** Downloads [url] to [target] and returns its SHA-256; failures are a [Stop]. */
    private suspend fun downloadOfficial(url: String, target: File, onStage: (PatchStage) -> Unit): String {
        val context = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val size = downloader.size(url)
            if (size != null) {
                val shortfall = blocking { reserve(workDir, roomFor(size)) }
                if (shortfall > 0) throw Stop(PatchFailure.NOT_ENOUGH_SPACE, shortfall)
            }
            onStage(PatchStage.Downloading(0, size ?: -1))
            downloader.download(url, target, digest) { read, total ->
                // Stops a blocking transfer promptly when the job is cancelled.
                context.ensureActive()
                onStage(PatchStage.Downloading(read, total))
            }
        } catch (error: NotEnoughSpaceException) {
            throw Stop(PatchFailure.NOT_ENOUGH_SPACE, error.bytesNeeded, error)
        } catch (error: IOException) {
            throw Stop(PatchFailure.NETWORK, cause = error)
        } catch (error: ApiException) {
            // whatsapp.com, not our server: only a transient answer is the network.
            throw Stop(if (error.isTransient) PatchFailure.NETWORK else PatchFailure.FAILED, cause = error)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** A server call, the bundle, the engine or the disk failed. */
    private suspend fun failureFor(error: Exception): PatchOutcome.Failed = when (error) {
        is StoreUpdateRequired ->
            failed(PatchFailure.STORE_UPDATE_REQUIRED, minStoreVersionCode = error.minStoreVersionCode, cause = error)
        is NotEnoughSpaceException -> failed(PatchFailure.NOT_ENOUGH_SPACE, error.bytesNeeded, cause = error)
        is ApiException -> failed(
            when {
                error.isAuth -> PatchFailure.SIGNED_OUT
                error.needsSubscription -> PatchFailure.NEEDS_SUBSCRIPTION
                error.isTransient -> PatchFailure.NETWORK
                else -> PatchFailure.FAILED
            },
            cause = error,
        )
        is PatchException.Unsupported, is PatchException.RecordMismatch ->
            failed(PatchFailure.NOT_SUPPORTED_YET, cause = error)
        is PatchException.NotOfficial -> failed(PatchFailure.NOT_OFFICIAL, cause = error)
        is PatchException.Storage -> failed(PatchFailure.NOT_ENOUGH_SPACE, cause = error)
        is IOException ->
            failed(if (isOutOfSpace(error)) PatchFailure.NOT_ENOUGH_SPACE else PatchFailure.FAILED, cause = error)
        else -> failed(PatchFailure.FAILED, cause = error)
    }

    /** A failure, unless the job was cancelled meanwhile: that is rethrown instead. */
    private suspend fun failed(
        reason: PatchFailure,
        bytesNeeded: Long = -1,
        minStoreVersionCode: Long? = null,
        cause: Throwable? = null,
    ): PatchOutcome.Failed {
        currentCoroutineContext().ensureActive()
        return PatchOutcome.Failed(reason, reason.message(bytesNeeded), minStoreVersionCode, cause)
    }

    /** Runs blocking work off the caller's thread; cancelling the job interrupts it. */
    private suspend fun <T> blocking(block: () -> T): T = runInterruptible(Dispatchers.IO, block)

    /** Deletes everything under [workDir] except [keep]. */
    private fun clearWorkDir(keep: File?) {
        workDir.walkBottomUp()
            .filter { it != workDir && it.absoluteFile != keep?.absoluteFile }
            .forEach { it.delete() }
    }

    /** Moves [source] to [target] so that [target] is never half a file. */
    private fun moveInto(source: File, target: File) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("No directory for $target")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create $dir")
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            // Another filesystem: copy beside the target, then rename there.
            val temp = File(dir, ".${target.name}.tmp")
            try {
                source.copyTo(temp, overwrite = true)
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temp.delete()
            }
            source.delete()
        }
    }

    /** The server's revision for this record (it covers the bundle too); null when it sent none. */
    private fun revisionOf(recordJson: String): Int? =
        (Json.parseToJsonElement(recordJson).jsonObject["revision"] as? JsonPrimitive)?.intOrNull

    /** A failure already classified where it happened. */
    private class Stop(val reason: PatchFailure, val bytesNeeded: Long = -1, cause: Throwable? = null) : Exception(cause)

    private companion object {
        /** ArtHooks needs Android 13. */
        const val MIN_SDK = 33
        const val HTTP_NOT_FOUND = 404
        const val OFFICIAL_APK = "official.apk"
        const val SIGNED_APK = "signed.apk"

        /** ceil(3.2 × [apkBytes]), in whole bytes: the engine's peak use is about 3×. */
        fun roomFor(apkBytes: Long): Long = (apkBytes * 16 + 4) / 5
    }
}
