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
import site.androidmod.store.data.AuthRepository
import site.androidmod.store.data.isAuth

/** Everything about the form but the text being typed; see [ChangePasswordViewModel.currentPassword]. */
data class ChangePasswordStatus(
    val saving: Boolean = false,
    /** The server's confirmation once the password is changed; the form is cleared then. */
    val done: String? = null,
    /** The server refused the current password. Shown on that field, not as a banner. */
    val currentPasswordError: String? = null,
    val error: String? = null,
)

/**
 * Changing the password while signed in, POST /api/users/me/password.
 *
 * Validated here against the server's own rules (8-128 characters, and not the
 * current password) so Save is only live for a change the server will take,
 * rather than letting the user find out from a 422.
 */
class ChangePasswordViewModel(
    private val auth: AuthRepository = StoreApp.instance.container.auth,
) : ViewModel() {

    /**
     * The fields' text, held as Compose state rather than in [status], for the
     * reason BugReportViewModel gives: a TextField whose value round-trips
     * through a collected StateFlow is updated a frame late and drops fast input.
     */
    var currentPassword by mutableStateOf("")
        private set
    var newPassword by mutableStateOf("")
        private set
    var confirmPassword by mutableStateOf("")
        private set

    private val _status = MutableStateFlow(ChangePasswordStatus())
    val status: StateFlow<ChangePasswordStatus> = _status.asStateFlow()

    /**
     * What is wrong with the new password, or null while it is fine or still
     * empty (an empty field is not an error yet, just not done).
     */
    val newPasswordProblem: String?
        get() {
            if (newPassword.isEmpty()) return null
            val length = newPassword.codePointLength()
            return when {
                length < MIN_PASSWORD -> NEW_PASSWORD_HINT
                length > MAX_PASSWORD -> "$MAX_PASSWORD characters at most"
                newPassword == currentPassword -> SAME_PASSWORD
                else -> null
            }
        }

    val confirmPasswordProblem: String?
        get() = if (confirmPassword.isNotEmpty() && confirmPassword != newPassword) "Passwords do not match." else null

    /** A change the server will take. Reads Compose state, so the screen follows typing. */
    val formValid: Boolean
        get() = currentPassword.isNotEmpty() && newPassword.isNotEmpty() && newPasswordProblem == null &&
            confirmPassword == newPassword

    val canSave: Boolean get() = formValid && !_status.value.saving

    fun onCurrentPasswordChange(value: String) {
        currentPassword = value
        // Retyping it answers the server's "incorrect" as well as any banner.
        if (_status.value.currentPasswordError != null || _status.value.error != null) {
            _status.update { it.copy(currentPasswordError = null, error = null) }
        }
    }

    fun onNewPasswordChange(value: String) {
        newPassword = value
        clearError()
    }

    fun onConfirmPasswordChange(value: String) {
        confirmPassword = value
        clearError()
    }

    private fun clearError() {
        if (_status.value.error != null) _status.update { it.copy(error = null) }
    }

    fun save() {
        if (!canSave) return
        val current = currentPassword
        val new = newPassword
        _status.update { it.copy(saving = true, currentPasswordError = null, error = null) }
        viewModelScope.launch {
            try {
                val done = auth.changePassword(current, new)
                currentPassword = ""
                newPassword = ""
                confirmPassword = ""
                _status.value = ChangePasswordStatus(done = done)
            } catch (error: ApiException) {
                // What was typed is kept on every failure, so a retry is one tap.
                _status.update {
                    when {
                        // The server answers a wrong current password with 400; see
                        // AuthRepository.changePassword.
                        error.status == 400 -> it.copy(saving = false, currentPasswordError = error.message)
                        error.status == 429 -> it.copy(saving = false, error = RATE_LIMITED)
                        else -> it.copy(saving = false, error = error.message)
                    }
                }
                // Like every other screen: a 401 here is a session that has ended,
                // and AuthViewModel puts the app back on the login screen.
                if (error.isAuth) auth.expireSession()
            }
        }
    }

    companion object {
        /** The server's bounds on a password (server/utils/passwords.py). */
        const val MIN_PASSWORD = 8
        const val MAX_PASSWORD = 128

        /**
         * The new password's supporting text, and also its "too short" problem: the
         * screen shows that one in the neutral colour, as the bug report form does
         * its minimum, so the field is not red for the first seven keystrokes.
         */
        const val NEW_PASSWORD_HINT = "At least $MIN_PASSWORD characters"

        const val SAME_PASSWORD = "Choose a password different from your current one"

        /** The server allows ten attempts an hour per IP; its own `detail` is generic. */
        const val RATE_LIMITED = "Too many attempts from your network. Please try again in an hour."
    }
}

/** Characters as the server counts them: Python's len() counts code points, not UTF-16 units. */
private fun String.codePointLength(): Int = codePointCount(0, length)
