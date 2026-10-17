package site.androidmod.patch

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.commons.compress.archivers.zip.ZipFile

/** Everything one patch run needs; [workDir] holds the intermediate unsigned APK. */
/** [paywallToken] is a secret: [toString] leaves it out, so a logged request never carries it. */
data class PatchRequest(
    val official: File,
    val bundle: PatchBundle,
    val record: VersionRecord,
    val paywallToken: String,
    val revision: Int,
    val workDir: File,
    val output: File,
) {
    override fun toString() =
        "PatchRequest(official=$official, bundle=$bundle, record=$record, revision=$revision, workDir=$workDir, output=$output)"
}

/** [visibleFeaturesOff] is [featuresOff] reduced to the titles worth showing the user. */
data class PatchResult(val output: File, val featuresOff: List<String>, val visibleFeaturesOff: List<String>)

/**
 * Ids of the features a patched build of [record] leaves off: those whose keys the
 * record lacks (or holds empty) first, then any ids only the record lists.
 */
fun featuresOff(features: Features, record: VersionRecord): List<String> {
    val present = record.artifacts.filterValues { it.isNotEmpty() }.keys
    val derivedOff = features.features.filter { f -> f.keys.any { it !in present } }.map { it.id }
    return derivedOff + record.featuresOff.filter { it !in derivedOff }
}

/** Verifies the official APK against a bundle and record, then builds and signs the patched APK. */
class PatchEngine(private val signer: ApkSigner) {
    private val json = Json

    /**
     * Peak disk use is about 3x the size of the official APK (the official APK itself, the unsigned
     * intermediate, and the signer's temp file next to the output); callers should reserve at
     * least 3.2x before calling.
     */
    fun patch(request: PatchRequest): PatchResult = try {
        run(request)
    } catch (e: PatchException) {
        throw e
    } catch (e: Exception) {
        throw PatchException.BadApk("Patching failed: ${e.message}", e)
    }

    private fun run(request: PatchRequest): PatchResult {
        val (official, bundle, record) = Triple(request.official, request.bundle, request.record)
        if (!record.supported) {
            throw PatchException.Unsupported("${record.packageName} ${record.versionName} is not supported")
        }
        val appManifest = readManifest(official)
        val appPackage = ManifestPatcher.packageName(appManifest)
        if (appPackage != bundle.meta.packageName || appPackage != record.packageName) {
            throw PatchException.RecordMismatch(
                "Package mismatch: APK $appPackage, bundle ${bundle.meta.packageName}, record ${record.packageName}",
            )
        }
        val manifestVersion = ManifestPatcher.versionCode(appManifest)
        if (manifestVersion != record.versionCode) {
            throw PatchException.RecordMismatch(
                "Version code mismatch: APK $manifestVersion, record ${record.versionCode}",
            )
        }
        val present = record.artifacts.filterValues { it.isNotEmpty() }.keys
        val missingRequired = bundle.features.requiredKeys.filter { it !in present }
        if (missingRequired.isNotEmpty()) {
            throw PatchException.Unsupported("${record.packageName} ${record.versionName} lacks required keys: $missingRequired")
        }
        val featuresOff = featuresOff(bundle.features, record)
        OfficialApk.verify(official, bundle.features.officialCertSha256)
        if (DexHash.dexSha256(official) != record.dexSha256) {
            throw PatchException.RecordMismatch("The record is for a different build of $appPackage")
        }

        val inputs = BuildInputs(
            manifest = ManifestPatcher.merge(appManifest, bundle.bytes("manifest-additions.xml")),
            modules = bundle.meta.dexOrder.map(bundle::bytes),
            jsonAssets = mapOf(
                "assets/patcher/artifactory.json" to artifactory(record),
                "assets/paywall.json" to bytes(buildJsonObject { put("token", request.paywallToken) }),
                "assets/patcher/patch-info.json" to bytes(
                    buildJsonObject {
                        put("patcher_version", bundle.meta.patcherVersion)
                        put("revision", request.revision)
                        put("dex_sha256", record.dexSha256)
                        put("features_off", JsonArray(featuresOff.map(::JsonPrimitive)))
                    },
                ),
            ),
        )

        val workDir = request.workDir
        if (!workDir.isDirectory && !workDir.mkdirs() && !workDir.isDirectory) {
            throw PatchException.Storage("cannot create work directory $workDir")
        }
        val unsigned = File(workDir, "unsigned.apk")
        try {
            ApkBuilder.build(official, inputs, unsigned)
            try {
                signer.sign(unsigned, request.output)
            } catch (e: IOException) {
                throw PatchException.Storage("cannot write ${request.output}: ${e.message}", e)
            }
        } finally {
            unsigned.delete()
        }
        return PatchResult(request.output, featuresOff, bundle.features.visibleTitles(featuresOff))
    }

    private fun artifactory(record: VersionRecord): ByteArray =
        bytes(
            JsonObject(
                record.artifacts.toSortedMap().mapValues { JsonPrimitive(it.value) },
            ),
        )

    private fun bytes(element: JsonElement): ByteArray =
        json.encodeToString(JsonElement.serializer(), element).toByteArray()

    private fun readManifest(apk: File): ByteArray = try {
        ZipFile.builder().setFile(apk).get().use { z ->
            val e = z.getEntry("AndroidManifest.xml") ?: throw PatchException.BadApk("APK has no AndroidManifest.xml")
            z.getInputStream(e).use { it.readBytes() }
        }
    } catch (e: IOException) {
        throw PatchException.BadApk("Cannot read the APK: ${e.message}", e)
    }
}
