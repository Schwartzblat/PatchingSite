package site.androidmod.store.data

import java.io.File
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

    suspend fun file(packageName: String): File? {
        val path = paths[packageName] ?: return null
        val target = File(dir, "$packageName.webp")
        if (target.isFile) return target
        return try {
            api.fetchFile(path, target, null)
            target.takeIf { it.isFile }
        } catch (error: ApiException) {
            null
        }
    }

    fun clear() {
        paths.clear()
        dir.deleteRecursively()
    }
}
