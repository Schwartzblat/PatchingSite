package site.androidmod.store.patch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Base64

/** The account's signing key as cached on this phone. Secret: [toString] hides the password. */
data class SigningKey(
    val keystore: File,
    val password: CharArray,
    val alias: String,
    val certSha256: String,
    val certSha1: String,
) {
    override fun toString() = "SigningKey(alias=$alias, certSha256=$certSha256)"

    // CharArray compares by identity in a data class; compare contents instead.
    override fun equals(other: Any?) = other is SigningKey && keystore == other.keystore &&
        password.contentEquals(other.password) && alias == other.alias &&
        certSha256 == other.certSha256 && certSha1 == other.certSha1

    override fun hashCode() = listOf(keystore, password.contentHashCode(), alias, certSha256, certSha1).hashCode()
}

/** `AA:BB:...` for a hex digest, the way Google's consoles show a certificate fingerprint. */
fun formatSha1(hex: String): String = hex.uppercase().chunked(2).joinToString(":")

@Serializable
private data class StoredKey(
    val password: String,
    val alias: String,
    @SerialName("cert_sha256") val certSha256: String,
    @SerialName("cert_sha1") val certSha1: String,
)

/**
 * One signing key per account, fetched from the server once and kept in the app's
 * private files ([dir] = filesDir/signing, excluded from backup) until sign-out.
 */
class SigningKeyStore(
    private val dir: File,
    private val fetch: suspend () -> SigningKeyResponse,
) {
    private val keystoreFile get() = File(dir, "signing-key.p12")
    private val metaFile get() = File(dir, "signing-key.json")
    private val lock = Mutex()

    /** The cached key, or null when nothing (or something unreadable) is stored. */
    fun cached(): SigningKey? = try {
        if (!keystoreFile.isFile || keystoreFile.length() == 0L) {
            null
        } else {
            val stored = Json.decodeFromString<StoredKey>(metaFile.readText())
            SigningKey(keystoreFile, stored.password.toCharArray(), stored.alias, stored.certSha256, stored.certSha1)
        }
    } catch (error: Exception) {
        null
    }

    suspend fun get(): SigningKey = lock.withLock {
        cached() ?: store(fetch())
    }

    fun clear() {
        keystoreFile.delete()
        metaFile.delete()
    }

    private fun store(response: SigningKeyResponse): SigningKey {
        val bytes = try {
            Base64.getMimeDecoder().decode(response.pkcs12)
        } catch (error: IllegalArgumentException) {
            throw IOException("The server sent a signing key that is not base64")
        }
        if (bytes.isEmpty()) throw IOException("The server sent an empty signing key")
        if (!HEX_64.matches(response.certSha256) || !HEX_40.matches(response.certSha1)) {
            throw IOException("The server sent a signing key with a malformed certificate digest")
        }
        val meta = StoredKey(response.password, response.alias, response.certSha256, response.certSha1)
        // Metadata first: cached() keys off the keystore file, so a crash in between stores nothing usable.
        writeAtomically(metaFile, Json.encodeToString(StoredKey.serializer(), meta).toByteArray())
        writeAtomically(keystoreFile, bytes)
        return SigningKey(keystoreFile, response.password.toCharArray(), response.alias, response.certSha256, response.certSha1)
    }

    private companion object {
        val HEX_64 = Regex("[0-9a-fA-F]{64}")
        val HEX_40 = Regex("[0-9a-fA-F]{40}")
    }
}
