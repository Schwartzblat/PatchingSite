package site.androidmod.patch

/** Where the hook library goes: the build to use and the `lib/` directory it is placed in. */
data class LibTarget(val hookAbi: String, val libDir: String)

/** Picks the hook-library ABI the way PackageManager picks the app's own ABI. */
object AbiChoice {
    private val HOOK_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    private fun family(abi: String): String = when (abi) {
        "arm64-v8a", "armeabi-v7a", "armeabi" -> "arm"
        "x86_64", "x86" -> "x86"
        else -> abi
    }

    fun choose(libAbis: Set<String>, deviceAbis: List<String>): Pair<LibTarget?, CantPatch?> {
        require(deviceAbis.isNotEmpty()) { "device reports no ABIs" }
        val appAbi = deviceAbis.firstOrNull { it in libAbis }
            ?: if (libAbis.isEmpty()) deviceAbis.first()
            else throw PatchException.BadApk("App libraries $libAbis fit none of the device ABIs $deviceAbis")
        // ArtHooks can't hook ART across a translator (e.g. an ARM app on an x86 device).
        if (family(appAbi) != family(deviceAbis.first())) return Pair(null, CantPatch.NEEDS_TRANSLATION)
        return when (appAbi) {
            // The v7a build, but under lib/armeabi/: a lib/armeabi-v7a dir would move the app off its own libs.
            "armeabi" -> Pair(LibTarget("armeabi-v7a", "armeabi"), null)
            in HOOK_ABIS -> Pair(LibTarget(appAbi, appAbi), null)
            else -> Pair(null, CantPatch.UNSUPPORTED_ABI)
        }
    }
}
