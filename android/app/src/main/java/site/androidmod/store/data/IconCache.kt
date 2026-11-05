package site.androidmod.store.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Icons the server supplies for apps the store does not bundle a drawable for. They
 * need the signed-in token, so they are fetched through [ApiClient] and kept in [dir],
 * which [clear] removes on sign-out.
 */
class IconCache(private val dir: File, private val api: ApiClient) {

    private val paths = ConcurrentHashMap<String, String>()

    /** Replaces the known icon paths with those of the catalogue that just loaded. */
    fun remember(apps: List<CatalogApp>) {
        paths.clear()
        apps.forEach { app -> app.icon?.let { paths[app.packageName] = it } }
    }

    fun pathFor(packageName: String): String? = paths[packageName]

    /** One lock per package: two icons for one app share a single download and temp file. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Never throws except for cancellation: a failed icon is a null, which shows the
     * monogram. Concurrent calls for one package queue on its lock, so the second finds
     * the file the first wrote instead of racing it for the same temp file.
     */
    suspend fun file(packageName: String): File? {
        val path = paths[packageName] ?: return null
        val target = File(dir, "$packageName.webp")
        if (target.isFile) return target
        return locks.getOrPut(packageName) { Mutex() }.withLock {
            if (target.isFile) return@withLock target
            try {
                api.fetchFile(path, target, null)
                target.takeIf { it.isFile }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                null
            } catch (error: IOException) {
                // A sign-out clearing the directory mid-fetch, or a full disk.
                null
            }
        }
    }

    fun clear() {
        paths.clear()
        dir.deleteRecursively()
    }
}
