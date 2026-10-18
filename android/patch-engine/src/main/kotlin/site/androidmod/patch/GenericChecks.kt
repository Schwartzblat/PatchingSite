package site.androidmod.patch

import java.io.File
import java.util.zip.ZipFile

/** Why an app can't be patched. Declaration order is the order reasons are listed in. */
enum class CantPatch { PAIRIP, SHARED_USER_ID, TARGET_SDK_TOO_OLD, ALREADY_PATCHED, NEEDS_TRANSLATION, UNSUPPORTED_ABI }

/** Whether the APKs being inspected are the app as installed from elsewhere, or our own patched copy. */
enum class GenericSource { ORIGINAL, PATCHED }

/** An app's patchability, from manifests and zip listings only. [libTarget] is null exactly when ABI choice gave a reason. */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val targetSdk: Int,
    val libAbis: Set<String>,
    val libTarget: LibTarget?,
    val cantPatch: List<CantPatch>,
)

object GenericChecks {
    private val OUR_ASSETS = listOf("assets/patcher/patch-info.json", "assets/patcher/artifactory.json", "assets/paywall.json")
    private val LIB_ENTRY = Regex("lib/([^/]+)/[^/]+\\.so")

    /** The lowest target SDK a device on [deviceSdk] will still install; 0 when it enforces none. */
    fun minInstallableTargetSdk(deviceSdk: Int): Int = when {
        deviceSdk >= 35 -> 24
        deviceSdk == 34 -> 23
        else -> 0
    }

    /**
     * Reads only manifests and zip listings (never dex), so the store's picker can call it per app.
     * [apks]`[0]` is the base; the rest are splits. [skipInBase] names base entries (e.g.
     * `lib/arm64-v8a/libhook.so`) that don't count towards the app's own ABIs.
     */
    fun inspect(
        apks: List<File>,
        deviceSdk: Int,
        deviceAbis: List<String>,
        source: GenericSource = GenericSource.ORIGINAL,
        skipInBase: Set<String> = emptySet(),
    ): ApkFacts {
        if (apks.isEmpty()) throw PatchException.BadApk("No APK given")
        val libAbis = linkedSetOf<String>()
        var base: ManifestFacts? = null
        var baseEntries = emptySet<String>()
        val splits = mutableSetOf<String>()
        var pairipLib = false
        for ((i, apk) in apks.withIndex()) {
            val (manifest, names) = try {
                ZipFile(apk).use { z ->
                    val entry = z.getEntry("AndroidManifest.xml")
                        ?: throw PatchException.BadApk("${apk.name} has no AndroidManifest.xml")
                    z.getInputStream(entry).use { it.readBytes() } to z.entries().asSequence().map { it.name }.toSet()
                }
            } catch (e: PatchException) {
                throw e
            } catch (e: Exception) {
                // java.util.zip also throws unchecked exceptions on malformed entries.
                throw PatchException.BadApk("Cannot read ${apk.name}: ${e.message}", e)
            }
            val facts = ManifestPatcher.facts(manifest)
            if (facts.versionCode == null) throw PatchException.BadApk("${apk.name} has no versionCode")
            if (i == 0) {
                if (facts.split != null) throw PatchException.BadApk("${apk.name} is a split, not a base APK")
                base = facts
                baseEntries = names
            } else {
                val b = base!!
                val split = facts.split ?: throw PatchException.BadApk("${apk.name} is not a split APK")
                if (!splits.add(split)) throw PatchException.BadApk("Two splits are named $split")
                if (facts.packageName != b.packageName) throw PatchException.BadApk("${apk.name} is for ${facts.packageName}")
                if (facts.versionCode != b.versionCode) throw PatchException.BadApk("${apk.name} has another versionCode")
            }
            for (name in names) {
                if (i == 0 && name in skipInBase) continue
                LIB_ENTRY.matchEntire(name)?.let { libAbis += it.groupValues[1] }
                if (name.startsWith("lib/") && name.endsWith("/libpairipcore.so")) pairipLib = true
            }
        }
        val b = base!!
        // PairIP's native lib can sit in any split, and its wrapper Application is named in the base manifest.
        val pairip = pairipLib || b.applicationName?.startsWith("com.pairip.") == true ||
            b.componentNames.any { it.startsWith("com.pairip.") }
        val targetSdk = b.targetSdk ?: b.minSdk ?: 1
        val (libTarget, abiReason) = AbiChoice.choose(libAbis, deviceAbis)
        val reasons = buildList {
            if (pairip) add(CantPatch.PAIRIP)
            if (b.sharedUserId != null) add(CantPatch.SHARED_USER_ID)
            if (targetSdk < minInstallableTargetSdk(deviceSdk)) add(CantPatch.TARGET_SDK_TOO_OLD)
            if (source == GenericSource.ORIGINAL && OUR_ASSETS.any { it in baseEntries }) add(CantPatch.ALREADY_PATCHED)
            if (abiReason != null) add(abiReason)
        }
        return ApkFacts(b.packageName, b.versionCode!!, b.versionName, targetSdk, libAbis, libTarget, reasons)
    }
}
