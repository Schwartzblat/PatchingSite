package site.androidmod.store.download

import java.io.File

/**
 * Where a downloaded build lands on disk.
 *
 * Kept apart from [DownloadService] so the containment rule below can be unit
 * tested without an Android runtime.
 */
object DownloadPaths {

    /** The subdirectory of `filesDir` every artifact lives in. */
    const val DIRECTORY = "downloads"

    /**
     * The file a build is streamed into.
     *
     * [key] carries a package name and version that came from the SERVER, and the
     * base URL is overridable from Settings, so a hostile server can choose both.
     * Interpolating them straight into a path lets one escape `filesDir/downloads`
     * - `../shared_prefs/auth.xml` being the obvious target. The resolved file is
     * therefore required to sit directly inside [directory], and anything else is
     * refused before a single byte is written.
     *
     * The suffix is kept from the server's own filename for the user's benefit
     * only - the installer reads the file to decide what it actually is.
     */
    fun targetFile(directory: File, key: DownloadKey, filename: String): File {
        val suffix = filename.substringAfterLast('.', "apk")
        val target = File(directory, "${key.packageName}-${key.version}.$suffix")
        // Canonical on both sides: `..` is resolved, and so is a symlinked filesDir
        // (`/data/user/0/<pkg>` really is `/data/data/<pkg>`), which a plain string
        // comparison of absolute paths would trip over.
        val root = directory.canonicalFile
        val resolved = target.canonicalFile
        require(resolved.parentFile == root) {
            "Refusing to write a download outside ${root.path}: ${resolved.path}"
        }
        return resolved
    }
}
