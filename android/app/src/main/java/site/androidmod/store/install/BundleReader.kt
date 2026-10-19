package site.androidmod.store.install

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipFile

class BundleException(message: String) : Exception(message)

/**
 * One APK to feed into an install session. [zipEntry] is null when a whole file is the
 * APK: [file] when set (a part of an [apkSetContents] set), else the downloaded file.
 */
data class ApkPart(val sessionName: String, val zipEntry: String?, val size: Long, val file: File? = null)

data class BuildContents(
    val parts: List<ApkPart>,
    val packageName: String?,
    val isBundle: Boolean,
)

private val SESSION_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9._-]*")

/**
 * The session parts for a set of separate APK files (a base and its splits): one part
 * per file, named after the file, in the given order, so `apks[0]` is the base. Pure.
 *
 * Throws [BundleException] for an empty list, two files with the same name, or a name
 * that is not safe as a session name (it is also typed into a `pm` command line).
 */
fun apkSetContents(apks: List<File>): BuildContents {
    if (apks.isEmpty()) throw BundleException("There is nothing to install.")
    val names = apks.map { it.name }
    if (names.toSet().size != names.size) throw BundleException("Two of the APK files have the same name.")
    names.firstOrNull { !SESSION_NAME.matches(it) }
        ?.let { throw BundleException("The APK file name \"$it\" cannot be used for an install.") }
    return BuildContents(
        parts = apks.map { ApkPart(sessionName = it.name, zipEntry = null, size = it.length(), file = it) },
        packageName = null,
        isBundle = apks.size > 1,
    )
}

/**
 * Works out what a downloaded build is by reading it.
 *
 * The server does the same: `utils/apps_utils.py::is_bundle` opens the zip and looks
 * for top-level `.apk` entries without ever consulting the filename. Trusting the extension would
 * fail a mislabelled artifact at commit time with an opaque parse error.
 */
object BundleReader {

    private val json = Json { ignoreUnknownKeys = true }

    fun read(file: File): BuildContents {
        if (!file.isFile || file.length() == 0L) {
            throw BundleException("The downloaded file is missing or empty.")
        }

        val zip = try {
            ZipFile(file)
        } catch (error: Exception) {
            throw BundleException("This file is not a readable Android package.")
        }

        zip.use {
            val entries = it.entries().toList()

            // TOP-LEVEL entries only. A split bundle keeps its APKs at the root of
            // the zip - verified against a real Moovit bundle, all 19 at the root and
            // none nested - whereas an ordinary APK can legitimately carry something
            // like `assets/helper.apk` inside it. Matching at any depth misreads that
            // single APK as a bundle and tries to install its nested payload as a
            // split. The server's is_bundle applies this same rule. It used Stitch's
            // depth-blind check until that served every single-APK WhatsApp build
            // unsigned, because Stitch embeds its helpers as assets/stitch/*.apk.
            val apks = entries.filter { entry ->
                !entry.isDirectory &&
                    !entry.name.contains('/') &&
                    entry.name.endsWith(".apk", ignoreCase = true)
            }

            // Scoped to bundles on purpose. An ordinary APK is itself a zip, and one
            // that happens to carry "assets/.../obb/..." inside is not shipping
            // expansion files - rejecting it would be wrong.
            if (apks.isNotEmpty() && entries.any { entry ->
                    entry.name.contains("/obb/", ignoreCase = true) ||
                        entry.name.endsWith(".obb", ignoreCase = true)
                }) {
                throw BundleException(
                    "This build ships expansion (OBB) files, which this app cannot install yet."
                )
            }

            if (apks.isEmpty()) {
                // An APK is itself a zip, so the absence of .apk entries means this
                // file should BE the package - verified, not assumed.
                val looksLikeApk = entries.any { entry ->
                    entry.name.equals("AndroidManifest.xml", ignoreCase = true)
                }
                if (!looksLikeApk) throw BundleException("This file is not an Android package.")
                return BuildContents(
                    parts = listOf(ApkPart(sessionName = "base", zipEntry = null, size = file.length())),
                    packageName = null,
                    isBundle = false,
                )
            }

            // Largest first, and called "base": conventional, and the session name is
            // only a key - Android reads each split's real name from its own manifest.
            val ordered = apks.sortedByDescending { entry -> entry.size }
            val used = mutableSetOf<String>()
            val parts = ordered.mapIndexed { index, entry ->
                val name = if (index == 0) "base" else uniqueName(entry.name, used)
                used += name
                ApkPart(sessionName = name, zipEntry = entry.name, size = entry.size)
            }

            return BuildContents(parts = parts, packageName = packageNameFrom(it), isBundle = true)
        }
    }

    fun openPart(file: File, part: ApkPart): InputStream {
        val entryName = part.zipEntry ?: return FileInputStream(part.file ?: file)
        val zip = ZipFile(file)
        // Everything between opening the archive and handing ownership to the
        // returned stream runs under this try. getEntry() succeeding only proves a
        // central-directory record exists; getInputStream() can still throw on a
        // corrupt or unsupported entry, and that would strand the handle. Across a
        // 19-split install that is 19 leaked handles per attempt.
        try {
            val entry = zip.getEntry(entryName)
                ?: throw BundleException("The build is missing $entryName.")
            val delegate = zip.getInputStream(entry)
            // From here the caller owns both: closing the stream closes the archive.
            return object : InputStream() {
                override fun read(): Int = delegate.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
                override fun available(): Int = delegate.available()
                override fun close() {
                    delegate.close()
                    zip.close()
                }
            }
        } catch (error: Throwable) {
            zip.close()
            throw error
        }
    }

    /** Read only for the package name, and only when present. Never required. */
    private fun packageNameFrom(zip: ZipFile): String? {
        val entry = zip.getEntry("manifest.json") ?: return null
        return runCatching {
            val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
            val root = json.parseToJsonElement(text) as? JsonObject ?: return null
            (root["package_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.getOrNull()
    }

    private fun uniqueName(entryName: String, used: Set<String>): String {
        val withoutSuffix = entryName.substringAfterLast('/').let { name ->
            // The entry filter is case-insensitive, so the strip must be too, or
            // "config.Apk" keeps its extension in the session name.
            if (name.endsWith(".apk", ignoreCase = true)) name.dropLast(4) else name
        }
        val base = withoutSuffix
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            // Dots and hyphens survive sanitisation, and both are dangerous at the
            // START of a session name. An entry called "...apk" would reduce to ".."
            // - a traversal token with no separator in it - and one called "-r.apk"
            // to "-r", which `pm install-write ... -r <session> -` parses as an
            // OPTION rather than a name. Trimming both from each end removes those
            // shapes while leaving "config.en" and "config.arm64_v8a" untouched.
            .trim('.', '-')
            .ifBlank { "split" }
        if (base !in used && base != "base") return base
        var suffix = 2
        while ("${base}_$suffix" in used) suffix++
        return "${base}_$suffix"
    }
}
