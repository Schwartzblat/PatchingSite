package site.androidmod.store.patch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import site.androidmod.patch.Features
import site.androidmod.patch.PatchBundle
import site.androidmod.patch.PatchException
import java.io.File
import java.security.PublicKey

/**
 * Patch bundles on disk: `dir/<pkg>/<patcherVersion>.zip` with the SHA-256 the server
 * sent beside it in `.sha256`, so the next request can ask "still current?" (304).
 * Whatever is on disk is re-verified against [publicKey] on every open.
 */
class BundleCache(
    private val dir: File,
    private val api: PatchApi,
    private val publicKey: PublicKey,
) {
    private val lock = Mutex()

    /** @throws PatchException.BadBundle when the bundle fails verification or is not the one asked for. */
    suspend fun open(pkg: String, patcherVersion: String): PatchBundle = lock.withLock {
        if (!SAFE_NAME.matches(pkg) || !SAFE_NAME.matches(patcherVersion)) {
            throw PatchException.BadBundle("unsafe bundle name")
        }
        val zip = File(dir, "$pkg/$patcherVersion.zip")
        val shaFile = File(dir, "$pkg/$patcherVersion.sha256")
        val cachedSha = if (zip.isFile) runCatching { shaFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } else null

        val fresh = api.bundle(pkg, patcherVersion, zip, cachedSha)
        if (fresh != null) writeAtomically(shaFile, fresh.toByteArray())

        withContext(Dispatchers.IO) {
            try {
                val bundle = PatchBundle.open(zip, publicKey)
                if (bundle.meta.packageName != pkg || bundle.meta.patcherVersion != patcherVersion) {
                    throw PatchException.BadBundle("bundle is for ${bundle.meta.packageName} ${bundle.meta.patcherVersion}")
                }
                bundle
            } catch (error: PatchException.BadBundle) {
                zip.delete()
                shaFile.delete()
                throw error
            }
        }
    }

    /** The cached bundle's feature list, or null when there is none or it does not verify. Never downloads. */
    fun cachedFeatures(pkg: String, patcherVersion: String): Features? {
        if (!SAFE_NAME.matches(pkg) || !SAFE_NAME.matches(patcherVersion)) return null
        val zip = File(dir, "$pkg/$patcherVersion.zip")
        if (!zip.isFile) return null
        return try {
            PatchBundle.open(zip, publicKey).features
        } catch (error: Exception) {
            null
        }
    }

    private companion object {
        val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
