package site.androidmod.store.data

import java.io.File

/**
 * The published build of this app itself. Both endpoints are public, so this
 * works the same signed in or not.
 *
 * Unlike a patched app, the store APK is served as-is - built already signed with
 * the shared key - so there is no job to enqueue and poll: the download starts
 * straight away and takes seconds, not minutes.
 */
class StoreUpdateRepository(private val api: ApiClient) {

    suspend fun info(): StoreInfo = api.decode(api.get("/api/store"))

    suspend fun download(target: File, onProgress: (bytesRead: Long, total: Long) -> Unit): File {
        api.download("/api/store/download", target, onProgress = onProgress)
        return target
    }
}
