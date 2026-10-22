package site.androidmod.patch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AdSdk(val name: String, val packages: List<String>, val hosts: List<String>)

@Serializable
data class IapSdkClient(val name: String, val packages: List<String>)

/** The optional `iap` section of a generic bundle's ad catalog. */
@Serializable
data class IapCatalogData(
    @SerialName("verifier_anchors") val verifierAnchors: List<String> = emptyList(),
    val clients: List<IapSdkClient> = emptyList(),
)

/** The ad catalog of a generic bundle (`catalog.json`), as data. */
@Serializable
data class AdCatalog(
    val format: Int,
    val sdks: List<AdSdk>,
    @SerialName("protected_hosts") val protectedHosts: List<String>,
    @SerialName("url_rules") val urlRules: List<List<String>>, // each exactly [host, path]
    @SerialName("app_frame_packages") val appFramePackages: List<String>,
    @SerialName("harvest_bare_tlds") val harvestBareTlds: List<String>,
    @SerialName("package_roots") val packageRoots: List<String>,
    val iap: IapCatalogData? = null,
)
