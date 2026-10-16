package site.androidmod.patch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Feature(val id: String, val title: String, val keys: List<String>, val visible: Boolean = true)

@Serializable
data class Features(
    @SerialName("required_keys") val requiredKeys: List<String>,
    @SerialName("official_cert_sha256") val officialCertSha256: List<String>,
    val features: List<Feature>,
) {
    /** Titles of the visible features among [ids], in `features.json` order. */
    fun visibleTitles(ids: List<String>): List<String> =
        features.filter { it.id in ids && it.visible }.map { it.title }
}
