package site.androidmod.patch

import com.android.apksig.ApkVerifier
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.apache.commons.compress.archivers.zip.ZipFile

/** What a generic patch is asked to do; each contributes its own artifacts to the patch. */
enum class Capability(val wire: String) {
    ADS("ads"),
    IAP("iap"),
    ;

    companion object {
        /** The capabilities named by wire name, in enum order. Unknown names are ignored, never refused. */
        fun of(names: Iterable<String>): Set<Capability> {
            val wanted = names.toSet()
            return entries.filterTo(LinkedHashSet()) { it.wire in wanted }
        }
    }
}

/**
 * One generic patch run. [apks] are the base, then its splits; [source] says whether they are the app as
 * installed from elsewhere or our own patched copy (a Refresh). Every output must be signed by the cert whose
 * SHA-256 is [signerCertSha256] and verify on [deviceSdk]. [capabilities] chooses what the patch does.
 * Intermediates go in [workDir]; the outputs go in [outputDir], each named as its input. An app in
 * [refusedPackages] (our own store's apps) is refused. [paywallToken] is a secret: [toString] leaves it out.
 */
data class GenericPatchRequest(
    val apks: List<File>,
    val source: GenericSource,
    val bundle: PatchBundle,
    val paywallToken: String,
    val signerCertSha256: String,
    val deviceSdk: Int,
    val deviceAbis: List<String>,
    val capabilities: Set<Capability> = setOf(Capability.ADS),
    val workDir: File,
    val outputDir: File,
    val refusedPackages: Set<String> = emptySet(),
) {
    override fun toString() =
        "GenericPatchRequest(apks=$apks, source=$source, bundle=$bundle, signerCertSha256=$signerCertSha256, " +
            "deviceSdk=$deviceSdk, deviceAbis=$deviceAbis, workDir=$workDir, outputDir=$outputDir)"
}

/** [apks] are the signed, verified outputs in input order; [artifacts] are the chosen capabilities' composed artifacts, as `artifactory.json` holds them. */
data class GenericPatchResult(
    val apks: List<File>,
    val facts: ApkFacts,
    val info: GenericPatchInfo,
    val artifacts: Map<String, String>,
)

/**
 * Patches any app with the generic bundle: the base gets the modules, the providers and our assets; every split
 * is copied unchanged and re-signed. A run returns a complete, verified set of outputs or throws, leaving none.
 */
class GenericPatchEngine(private val signer: ApkSigner) {
    fun patch(request: GenericPatchRequest): GenericPatchResult = try {
        run(request)
    } catch (e: PatchException) {
        throw e
    } catch (e: Exception) {
        throw PatchException.BadApk("Patching failed: ${e.message}", e)
    }

    private fun run(r: GenericPatchRequest): GenericPatchResult {
        if (r.capabilities.isEmpty()) throw PatchException.Unsupported("nothing to patch")
        val bundle = r.bundle
        if (!bundle.isGeneric) throw PatchException.BadBundle("a ${bundle.meta.packageName} bundle is not the generic bundle")
        val catalog = bundle.catalog ?: throw PatchException.BadBundle("the generic bundle has no catalog")
        checkInputs(r)
        val base = r.apks.first()

        // A Refresh from our patched copy leaves out everything the last build added, and starts from the app's manifest.
        val drop: Set<String>
        val appManifest: ByteArray
        when (r.source) {
            GenericSource.ORIGINAL -> {
                drop = emptySet()
                appManifest = entry(base, MANIFEST)
            }
            GenericSource.PATCHED -> {
                val info = GenericPatchInfo.read(base) ?: throw PatchException.BadApk("${base.name} has no generic patch-info")
                drop = info.added.toSet()
                appManifest = entry(base, GenericPatchInfo.ORIGINAL_MANIFEST)
            }
        }

        val facts = GenericChecks.inspect(r.apks, r.deviceSdk, r.deviceAbis, r.source, drop)
        val scan = AdScan.scan(r.apks, catalog, drop)
        val fromScan = listOfNotNull(CantPatch.PAIRIP.takeIf { scan.pairip }, CantPatch.ALREADY_PATCHED.takeIf { scan.patchedBefore })
        val ours = listOfNotNull(CantPatch.IN_OUR_STORE.takeIf { facts.packageName in r.refusedPackages })
        val reasons = (facts.cantPatch + fromScan + ours).distinct().sorted()
        if (reasons.isNotEmpty()) throw PatchException.Unpatchable(reasons)
        val libs = checkNotNull(facts.libTarget) { "no hook ABI without a reason" }

        // The artifacts are what the chosen capabilities found: no key of an unchosen capability appears at all.
        val artifacts = LinkedHashMap<String, String>()
        if (Capability.ADS in r.capabilities) artifacts.putAll(scan.artifacts)
        if (Capability.IAP in r.capabilities) {
            artifacts["IAP_ENABLED"] = "true"
            artifacts.putAll(IapScan.scan(r.apks, catalog.iap).artifacts)
        }

        val additions = bundle.bytes(ADDITIONS)
        val newPrefix = "${facts.packageName}."
        val manifest = ManifestPatcher.merge(appManifest, ManifestPatcher.rewriteAuthorities(additions, AUTHORITY_PREFIX, newPrefix))
        // A leftover placeholder authority would fail the install (INSTALL_FAILED_CONFLICTING_PROVIDER) after the original is gone.
        // Only the bundle's own placeholders count: the app may declare "generic." authorities, and an app named
        // "generic" is rewritten onto the same prefix.
        if (newPrefix != AUTHORITY_PREFIX) {
            val placeholders = ManifestPatcher.providerAuthorities(additions).filter { it.startsWith(AUTHORITY_PREFIX) }.toSet()
            ManifestPatcher.providerAuthorities(manifest).firstOrNull { it in placeholders }?.let {
                throw PatchException.BadBundle("provider authority $it was not rewritten to ${facts.packageName}")
            }
        }
        val inputs = BuildInputs(
            manifest = manifest,
            modules = bundle.meta.dexOrder.map(bundle::bytes),
            jsonAssets = mapOf(
                JsonAssets.ARTIFACTORY to JsonAssets.artifactory(artifacts),
                JsonAssets.PAYWALL to JsonAssets.paywall(r.paywallToken),
                GenericPatchInfo.ORIGINAL_MANIFEST to appManifest,
            ),
        )
        var info: GenericPatchInfo? = null
        val options = BuildOptions(
            drop = drop,
            libs = libs,
            refuseCollisions = true,
            describe = { others ->
                val added = (others + GenericPatchInfo.ENTRY).sorted()
                val described = GenericPatchInfo(
                    GenericPatchInfo.KIND, bundle.meta.patcherVersion, bundle.id, catalog.format,
                    libs.hookAbi, libs.libDir, scan.artifacts.getValue(SDKS_PRESENT), added,
                    Capability.entries.filter { it in r.capabilities }.map { it.wire },
                )
                info = described
                GenericPatchInfo.ENTRY to GenericPatchInfo.encode(described)
            },
        )

        mkdirs(r.workDir)
        val unsigned = File(r.workDir, "generic-base.unsigned.apk")
        val outputs = try {
            ApkBuilder.build(base, inputs, unsigned, options)
            checkNotNull(info) { "the build did not describe itself" }
            signAndVerify(r, facts, unsigned)
        } finally {
            unsigned.delete()
        }
        return GenericPatchResult(outputs, facts, checkNotNull(info), artifacts)
    }

    private fun checkInputs(r: GenericPatchRequest) {
        if (r.apks.isEmpty()) throw PatchException.BadApk("No APK given")
        val names = HashSet<String>()
        for (apk in r.apks) {
            // The outputs are named after the inputs.
            if (!names.add(apk.name)) throw PatchException.BadApk("Two input APKs are named ${apk.name}")
            if (File(r.outputDir, apk.name).canonicalFile == apk.canonicalFile) {
                throw PatchException.BadApk("${apk.name} would be overwritten by its own output")
            }
        }
    }

    /** Signs the base and then each split into [GenericPatchRequest.outputDir], and verifies them all, or leaves none there. */
    private fun signAndVerify(r: GenericPatchRequest, facts: ApkFacts, unsignedBase: File): List<File> {
        mkdirs(r.outputDir)
        val outputs = r.apks.map { File(r.outputDir, it.name) }
        val written = ArrayList<File>()
        var done = false
        try {
            sign(unsignedBase, outputs[0], written)
            unsignedBase.delete() // the splits need the room
            for (i in 1 until r.apks.size) {
                val stripped = File(r.workDir, "generic-split.unsigned.apk")
                try {
                    ApkBuilder.stripV1(r.apks[i], stripped)
                    sign(stripped, outputs[i], written)
                } finally {
                    stripped.delete()
                }
            }
            for (apk in outputs) verify(apk, r, facts)
            done = true
        } finally {
            if (!done) written.forEach { it.delete() }
        }
        return outputs
    }

    private fun sign(unsigned: File, signed: File, written: MutableList<File>) {
        written += signed
        try {
            signer.sign(unsigned, signed)
        } catch (e: PatchException) {
            throw e
        } catch (e: IOException) {
            throw PatchException.Storage("cannot write ${signed.name}: ${e.message}", e)
        } catch (e: Exception) {
            throw PatchException.SignFailed("cannot sign ${signed.name}: ${e.message}", e)
        }
    }

    private fun verify(apk: File, r: GenericPatchRequest, facts: ApkFacts) {
        val result = try {
            ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(r.deviceSdk).build().verify()
        } catch (e: Exception) {
            throw PatchException.SignFailed("cannot verify ${apk.name}: ${e.message}", e)
        }
        if (!result.isVerified) {
            val why = result.errors.joinToString("; ").ifEmpty { "no valid signature" }
            throw PatchException.SignFailed("${apk.name} does not verify: $why")
        }
        val certs = result.signerCertificates + result.v2SchemeSigners.map { it.certificate } +
            result.v3SchemeSigners.map { it.certificate } + result.v31SchemeSigners.map { it.certificate }
        // A malformed signing block can yield null certificates despite the platform types.
        if (certs.filterNotNull().none { sha256Hex(it.encoded).equals(r.signerCertSha256, ignoreCase = true) }) {
            throw PatchException.SignFailed("${apk.name} is not signed with the expected certificate")
        }
        val m = try {
            ManifestPatcher.facts(entry(apk, MANIFEST))
        } catch (e: PatchException) {
            throw PatchException.SignFailed("cannot read the manifest of ${apk.name}: ${e.message}", e)
        }
        if (m.packageName != facts.packageName || m.versionCode != facts.versionCode) {
            throw PatchException.SignFailed(
                "${apk.name} is ${m.packageName} ${m.versionCode}, not ${facts.packageName} ${facts.versionCode}",
            )
        }
    }

    private fun entry(apk: File, name: String): ByteArray = try {
        ZipFile.builder().setFile(apk).get().use { z ->
            val e = z.getEntry(name) ?: throw PatchException.BadApk("${apk.name} has no $name")
            z.getInputStream(e).use { it.readBytes() }
        }
    } catch (e: IOException) {
        throw PatchException.BadApk("Cannot read ${apk.name}: ${e.message}", e)
    }

    private fun mkdirs(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw PatchException.Storage("cannot create directory $dir")
    }

    companion object {
        private const val MANIFEST = "AndroidManifest.xml"
        private const val ADDITIONS = "manifest-additions.xml"
        private const val AUTHORITY_PREFIX = "generic."
        private const val SDKS_PRESENT = "AD_SDKS_PRESENT"
        private const val SLACK_BYTES = 8L shl 20

        /**
         * Disk a run needs in its work and output dirs, at the moment the last split is signed: every APK's size
         * (the signed base and splits), the base and modules once more (the unsigned base, which exists until the
         * base is signed, and the signed base), the largest split once more (the stripped copy being signed, and
         * its signer's temp copy), and 8 MiB of slack. The saved original is the caller's.
         */
        fun bytesNeeded(apks: List<File>, bundle: PatchBundle): Long {
            val modules = bundle.meta.dexOrder.sumOf { bundle.bytes(it).size.toLong() }
            val largestSplit = apks.drop(1).maxOfOrNull { it.length() } ?: 0L
            return apks.sumOf { it.length() } + 2 * ((apks.firstOrNull()?.length() ?: 0L) + modules) + largestSplit + SLACK_BYTES
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
