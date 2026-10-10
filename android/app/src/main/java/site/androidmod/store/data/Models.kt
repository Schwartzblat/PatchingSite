package site.androidmod.store.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Long = 0,
)

@Serializable
data class User(
    val username: String,
    val email: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /**
     * Nullable, and only an explicit `false` counts; read it through
     * [needsVerification]. A server that predates verification sends no field,
     * and reading that as "unverified" would put a verify-your-email card, whose
     * Resend button the old server would 404, in front of every account. Accounts
     * that existed before verification shipped are verified server-side anyway.
     */
    @SerialName("email_verified") val emailVerified: Boolean? = null,
) {
    /** True only when the server said so; see [emailVerified]. */
    val needsVerification: Boolean get() = emailVerified == false
}

/**
 * POST /api/register. [verificationSent] is null from a server that predates
 * verification, and false when it could not send the email (no SMTP configured,
 * or the day's mail budget is spent) - the account exists either way.
 */
@Serializable
data class RegisterResponse(
    val message: String? = null,
    @SerialName("verification_sent") val verificationSent: Boolean? = null,
)

/** A `{"message": "..."}` answer, such as resend-verification's. */
@Serializable
data class MessageResponse(val message: String? = null)

/** One entry of GET /api/apps. `package` is a Kotlin keyword, hence the rename. */
@Serializable
data class CatalogApp(
    val key: String,
    val name: String,
    @SerialName("package") val packageName: String,
    val features: List<String> = emptyList(),
)

/** One entry of GET /api/list/{package}. `type` is advisory - the installer reads the file. */
@Serializable
data class BuildVersion(
    val version: String,
    val type: String,
    val filename: String,
    val size: Long,
    @SerialName("updated_at") val updatedAt: String,
    /**
     * Optional on purpose. The deployed server at androidmod.site is older than
     * this repo and sends `href` instead of `id`; requiring `id` makes the whole
     * listing fail to deserialise against it. The web client already falls back
     * to the version for its list key (PatchedAppPage.jsx `versionKey`), so this
     * does the same rather than inventing a stricter contract than the server has.
     */
    val id: String? = null,
) {
    /** Stable identity for list keys, whichever shape the server sent. */
    val key: String get() = id ?: version
}

@Serializable
data class CreatedJob(
    @SerialName("job_id") val jobId: String,
    val state: String,
    val cached: Boolean = false,
)

@Serializable
data class JobStatus(
    @SerialName("job_id") val jobId: String,
    val state: String,
    val progress: Double = 0.0,
    val message: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    val filename: String? = null,
    val size: Long? = null,
) {
    val isTerminal: Boolean get() = state == READY || state == FAILED

    companion object {
        const val QUEUED = "queued"
        const val RUNNING = "running"
        const val READY = "ready"
        const val FAILED = "failed"
    }
}

@Serializable
data class SubscriptionEnvelope(val subscription: Subscription? = null)

@Serializable
data class Subscription(
    val planId: String? = null,
    val planName: String? = null,
    val status: String,
    val currentPeriodEnd: String? = null,
    val cancelAtPeriodEnd: Boolean = false,
) {
    val isActive: Boolean get() = status in ACTIVE_STATUSES

    companion object {
        val ACTIVE_STATUSES = setOf("active", "trialing")
    }
}

@Serializable
data class UrlResponse(val url: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RegisterRequest(val username: String, val email: String, val password: String)

@Serializable
data class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class DownloadRequest(
    @SerialName("package") val packageName: String,
    val version: String,
)

/**
 * POST /api/bug-reports. [source] has no default on purpose: ApiClient's Json
 * does not encode default values, so a defaulted "android" would never be sent.
 * [email] is the opposite case - null is left out, and the server then replies to
 * the signed-in account's address.
 */
@Serializable
data class BugReportRequest(
    val message: String,
    val email: String? = null,
    val source: String,
    val context: Map<String, String>,
)

@Serializable
data class BugReportResponse(
    val id: Long? = null,
    val message: String = "Thanks! Your report was sent.",
)

/**
 * GET /api/store: the published build of this app.
 *
 * [versionCode] is read by the server out of the APK's own manifest, and is null
 * when it could not be read - or when the server predates the field. Null means
 * "cannot be ordered against the installed build", never "no update".
 */
@Serializable
data class StoreInfo(
    val available: Boolean,
    val size: Long = 0,
    @SerialName("version_code") val versionCode: Long? = null,
    @SerialName("version_name") val versionName: String? = null,
)
