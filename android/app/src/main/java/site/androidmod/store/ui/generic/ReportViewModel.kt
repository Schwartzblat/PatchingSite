package site.androidmod.store.ui.generic

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
import site.androidmod.store.data.ApiException
import site.androidmod.store.generic.GenericApi
import site.androidmod.store.generic.GenericRecord
import site.androidmod.store.generic.payloadLines
import site.androidmod.store.generic.reportPayload

/** Everything about the report form but the choice and the note; see [ReportViewModel.note]. */
data class ReportStatus(
    val sending: Boolean = false,
    /** The server's thanks once the report is in. */
    val sent: String? = null,
    val error: String? = null,
)

/**
 * "Report how it works" for one generic patch, POST /api/generic/reports. Nothing is sent until
 * [send]: opening the screen, choosing and typing stay on the phone. What [send] posts is exactly
 * the request whose [preview] is on screen at that moment.
 */
class ReportViewModel(
    private val record: GenericRecord,
    private val api: GenericApi,
    private val storeVersionCode: Long,
    private val sdkInt: Int,
) : ViewModel() {

    val packageName: String get() = record.packageName
    val label: String get() = record.label

    /** Compose state, as BugReportViewModel holds its fields: a TextField round-tripping a StateFlow drops input. */
    var worked by mutableStateOf<Boolean?>(null)
        private set
    var note by mutableStateOf("")
        private set

    private val _status = MutableStateFlow(ReportStatus())
    val status: StateFlow<ReportStatus> = _status.asStateFlow()

    /** Characters as the server counts them (code points of the stripped note). */
    val noteLength: Int get() = note.trim().let { it.codePointCount(0, it.length) }

    /** A choice is made and the note fits. Reads Compose state, so the screen follows typing. */
    val formValid: Boolean get() = worked != null && noteLength <= MAX_NOTE

    val canSend: Boolean get() = formValid && !_status.value.sending

    /** The lines of the report as it stands; empty until a choice is made. */
    val preview: List<String> get() = request()?.let(::payloadLines).orEmpty()

    fun onWorked(value: Boolean) {
        worked = value
        clearError()
    }

    fun onNoteChange(value: String) {
        note = value
        clearError()
    }

    fun send() {
        if (!canSend) return
        val request = request() ?: return
        _status.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                _status.value = ReportStatus(sent = api.report(request))
            } catch (error: ApiException) {
                // The form stays as it was, so it can be sent again.
                _status.update {
                    it.copy(sending = false, error = if (error.status == HTTP_TOO_MANY) RATE_LIMITED else error.message)
                }
            }
        }
    }

    private fun request() = worked?.let { reportPayload(record, it, note, storeVersionCode, sdkInt) }

    private fun clearError() {
        if (_status.value.error != null) _status.update { it.copy(error = null) }
    }

    companion object {
        /** The server's cap on the note. */
        const val MAX_NOTE = 500

        /** The server takes a few reports per account a day. */
        const val RATE_LIMITED = "You've sent several reports today. Try again tomorrow."

        private const val HTTP_TOO_MANY = 429
    }
}
