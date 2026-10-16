package site.androidmod.store.data

import java.net.URLEncoder

class CatalogRepository(
    private val api: ApiClient,
    /** Told about every catalogue that loaded, e.g. to remember which apps patch on the phone. */
    private val onApps: (List<CatalogApp>) -> Unit = {},
) {

    suspend fun apps(): List<CatalogApp> =
        api.decode<List<CatalogApp>>(api.get("/api/apps")).also(onApps)

    suspend fun versions(packageName: String): List<BuildVersion> =
        api.decode(api.get("/api/list/${encode(packageName)}"))

    /** Package names are safe today, but the path segment is built from server data. */
    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
