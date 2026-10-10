package site.androidmod.store.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import site.androidmod.store.StoreApp
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.BugReportRepository
import site.androidmod.store.ui.SiteLinks
import site.androidmod.store.update.InstalledBuild

/** Everything about the form but the text being typed; see [BugReportViewModel.message]. */
data class BugReportStatus(
    val sending: Boolean = false,
    /** The server's confirmation once the report is in; the form is cleared then. */
    val sent: String? = null,
    val error: String? = null,
)

/**
 * The in-app bug report form, POST /api/bug-reports.
 *
 * Validated here against the server's own limits (10-5000 characters, an email of
 * at most 254) so the Send button is only live for a report the server will take,
 * rather than letting the user find out from a 422.
 */
class BugReportViewModel(
    private val repository: BugReportRepository = StoreApp.instance.container.bugReports,
    /** Attached to every report: app version, OS and device. See [bugReportContext]. */
    private val deviceContext: Map<String, String> = StoreApp.instance.container.deviceContext(),
    /**
     * The signed-in account's address, so the usual case needs no typing. Editable,
     * because the address someone wants a reply at is not always the account's.
     */
    defaultEmail: String? = null,
) : ViewModel() {

    /**
     * The fields' text, held as Compose state rather than in [status]. A TextField
     * whose value round-trips through a StateFlow collected by the screen is
     * updated a frame late, and fast typing, autocorrect and swipe input then drop
     * characters or jump the cursor - the pattern Compose's own guidance warns
     * against, in a field that takes up to 5000 characters. Writes here are seen
     * by the TextField synchronously.
     */
    var message by mutableStateOf("")
        private set
    var email by mutableStateOf(defaultEmail.orEmpty())
        private set

    private val _status = MutableStateFlow(BugReportStatus())
    val status: StateFlow<BugReportStatus> = _status.asStateFlow()

    /**
     * Characters as the server counts them: Python's len() counts code points,
     * where String.length counts UTF-16 units and an emoji is two of those. The
     * trimmed text, because that is what is sent.
     */
    val messageLength: Int get() = message.trim().let { it.codePointCount(0, it.length) }

    /** Blank is fine - the email is optional - but a filled-in one must be replyable. */
    val emailInvalid: Boolean get() = email.trim().let { it.isNotEmpty() && !looksLikeEmail(it) }

    /** The text is a report the server will take. Reads Compose state, so the screen follows typing. */
    val formValid: Boolean get() = messageLength in MIN_MESSAGE..MAX_MESSAGE && !emailInvalid

    val canSend: Boolean get() = formValid && !_status.value.sending

    fun onMessageChange(value: String) {
        message = value
        clearError()
    }

    fun onEmailChange(value: String) {
        email = value
        clearError()
    }

    private fun clearError() {
        if (_status.value.error != null) _status.update { it.copy(error = null) }
    }

    fun send() {
        if (!canSend) return
        val text = message.trim()
        val replyTo = email.trim().ifEmpty { null }
        _status.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                val thanks = repository.send(message = text, email = replyTo, context = deviceContext)
                message = ""
                _status.value = BugReportStatus(sent = thanks)
            } catch (error: ApiException) {
                // The form is kept on every failure: a report someone took the
                // time to write must not vanish because the network did.
                _status.update {
                    it.copy(sending = false, error = if (error.status == 429) RATE_LIMITED else error.message)
                }
            }
        }
    }

    companion object {
        /** The server's bounds on `message`. */
        const val MIN_MESSAGE = 10
        const val MAX_MESSAGE = 5000

        /**
         * The server allows about five reports an hour per IP, so a 429 means "not
         * now" rather than "never". Its own `detail` is generic; this says what to
         * do, including the way around the limit.
         */
        const val RATE_LIMITED =
            "You've sent several reports in the last hour. Please try again later, " +
                "or email ${SiteLinks.SUPPORT_EMAIL}."
    }
}

private const val MAX_EMAIL = 254

/**
 * Enough to catch a typo that would make a reply impossible - a missing @, a
 * missing domain - without claiming to validate addresses, which the server does.
 */
private fun looksLikeEmail(value: String): Boolean {
    if (value.length > MAX_EMAIL || value.any { it.isWhitespace() }) return false
    val at = value.lastIndexOf('@')
    return at > 0 && at < value.length - 1 && '.' in value.substring(at + 1)
}

/** The server's cap on each context value. */
private const val MAX_CONTEXT_VALUE = 500

/**
 * What every report carries about where it came from, so the owner does not have
 * to ask. Built from the installed package rather than BuildConfig, for the same
 * reason the updater does: release stamps its versionCode at build time.
 */
fun bugReportContext(
    installed: InstalledBuild,
    release: String,
    sdk: Int,
    manufacturer: String,
    model: String,
): Map<String, String> = mapOf(
    "app_version" to "${installed.versionName} (${installed.versionCode})",
    "os" to "Android $release (API $sdk)",
    // "Google Pixel 9a", but "Samsung Galaxy S23" rather than "samsung Samsung
    // Galaxy S23": some makers already put their name in the model string.
    "device" to if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model",
).mapValues { (_, value) -> value.take(MAX_CONTEXT_VALUE) }
