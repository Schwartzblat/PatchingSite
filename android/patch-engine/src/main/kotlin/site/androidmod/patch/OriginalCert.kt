package site.androidmod.patch

import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.zip.ZipFile

/** The SHA-1 of the app's original signing certificate, which the patched app can no longer show for itself. */
internal object OriginalCert {
    const val KEY = "ORIGINAL_CERT_SHA1"
    private val SHA1 = Regex("[0-9a-fA-F]{40}")

    /** The uppercase SHA-1 of the one certificate [apk] verifies under on [deviceSdk]; null when unsigned, invalid or signed by several. */
    fun of(apk: File, deviceSdk: Int): String? = try {
        val result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(deviceSdk)
            .setMaxCheckedPlatformVersion(deviceSdk).build().verify()
        if (!result.isVerified) {
            null
        } else {
            val certs = listOf(
                result.v31SchemeSigners.map { it.certificate },
                result.v3SchemeSigners.map { it.certificate },
                result.v2SchemeSigners.map { it.certificate },
                result.signerCertificates,
            ).firstOrNull { it.isNotEmpty() }.orEmpty()
            val encodings = certs.map { it.encoded.toList() }.distinct()
            if (encodings.size == 1) sha1(encodings[0].toByteArray()) else null
        }
    } catch (e: Exception) {
        null
    }

    /** The value a previous patch recorded in [patchedBase]'s artifactory, uppercased; null when absent or malformed. */
    fun carried(patchedBase: File): String? = try {
        val json = ZipFile.builder().setFile(patchedBase).get().use { z ->
            val e = z.getEntry(JsonAssets.ARTIFACTORY) ?: return null
            z.getInputStream(e).use { String(it.readBytes()) }
        }
        Json.parseToJsonElement(json).jsonObject[KEY]?.jsonPrimitive?.content
            ?.takeIf { SHA1.matches(it) }?.uppercase()
    } catch (e: Exception) {
        null
    }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02X".format(it) }
}
