package site.androidmod.patch

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.commons.compress.archivers.zip.ZipFile

/** Everything one patch run needs; [workDir] holds the intermediate unsigned APK. */
data class PatchRequest(
    val official: File,
    val bundle: PatchBundle,
    val record: VersionRecord,
    val paywallToken: String,
    val revision: Int,
    val workDir: File,
    val output: File,
)

/** [visibleFeaturesOff] is [featuresOff] reduced to the titles worth showing the user. */
data class PatchResult(val output: File, val featuresOff: List<String>, val visibleFeaturesOff: List<String>)

/** Verifies the official APK against a bundle and record, then builds and signs the patched APK. */
class PatchEngine(private val signer: ApkSigner) {
    private val json = Json

    fun patch(request: PatchRequest): PatchResult {
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
                    },
                ),
            ),
        )

        request.workDir.mkdirs()
        val unsigned = File(request.workDir, "unsigned.apk")
        try {
            ApkBuilder.build(official, inputs, unsigned)
            signer.sign(unsigned, request.output)
        } finally {
            unsigned.delete()
        }
        return PatchResult(request.output, record.featuresOff, bundle.features.visibleTitles(record.featuresOff))
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
        throw PatchException.BadApk("Cannot read the APK: ${e.message}")
    }
}
