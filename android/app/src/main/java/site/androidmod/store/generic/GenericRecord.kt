package site.androidmod.store.generic

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import site.androidmod.store.patch.writeAtomically

enum class GenericIntent { PATCH, RESTORE, REFRESH }

@Serializable
data class SavedApk(val name: String, val size: Long, val sha256: String)

/**
 * Everything the store knows about one generically patched app, as facts. The current step is
 * never stored: [genericPhase] derives it from this plus what is installed, so a store killed
 * at any point resumes correctly.
 */
@Serializable
data class GenericRecord(
    @SerialName("package") val packageName: String,
    val label: String,
    @SerialName("version_name") val versionName: String,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("original_signers") val originalSigners: Set<String>,
    /** File names of the installed APKs when the flow began, base first. */
    @SerialName("apk_names") val apkNames: List<String>,
    /** Non-empty only once every copy is verified. */
    val original: List<SavedApk> = emptyList(),
    /** Base first; non-empty only once verified. */
    val built: List<SavedApk> = emptyList(),
    @SerialName("built_bundle_id") val builtBundleId: String? = null,
    @SerialName("built_token_expires_at") val builtTokenExpiresAt: Long? = null,
    /** The SHA-256 of the certificate [built] is signed with; null in a record from before it was kept. */
    @SerialName("built_signer") val builtSigner: String? = null,
    val intent: GenericIntent? = null,
    @SerialName("uninstall_asked_at") val uninstallAskedAt: Long? = null,
    @SerialName("patched_bundle_id") val patchedBundleId: String? = null,
    @SerialName("token_expires_at") val tokenExpiresAt: Long? = null,
    @SerialName("patched_at") val patchedAt: Long? = null,
)

private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

/** Whether [name] is a package name [GenericRecords] accepts; anything else it refuses with IllegalArgumentException. */
fun isPackageName(name: String): Boolean = PACKAGE_NAME.matches(name)

/** One directory per package under [root] (filesDir/generic), holding record.json, original/ and built/. Open for tests' put hooks. */
open class GenericRecords(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Unreadable record files are skipped, never deleted. */
    fun all(): List<GenericRecord> =
        root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { read(File(it, RECORD)) }

    fun get(packageName: String): GenericRecord? = read(File(packageDir(packageName), RECORD))

    /** Atomic and synced to disk before it returns. */
    open fun put(record: GenericRecord) {
        writeAtomically(File(packageDir(record.packageName), RECORD), json.encodeToString(record).toByteArray())
    }

    fun remove(packageName: String) {
        packageDir(packageName).deleteRecursively()
    }

    fun originalDir(packageName: String): File = File(packageDir(packageName), "original")
    fun builtDir(packageName: String): File = File(packageDir(packageName), "built")
    fun builtTmpDir(packageName: String): File = File(packageDir(packageName), "built.tmp")

    private fun packageDir(packageName: String): File {
        require(PACKAGE_NAME.matches(packageName)) { "Not a package name: $packageName" }
        return File(root, packageName)
    }

    private fun read(file: File): GenericRecord? = try {
        if (file.isFile) json.decodeFromString<GenericRecord>(file.readText()) else null
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val RECORD = "record.json"
    }
}
