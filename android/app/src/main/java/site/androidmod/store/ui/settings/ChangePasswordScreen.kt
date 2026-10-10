package site.androidmod.store.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.androidmod.store.R
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.theme.PapSuccess

/**
 * "Change password", reached from Settings > Account. The title and back arrow are
 * the Scaffold's, like every other signed-in screen.
 *
 * Native rather than the website's form in a Custom Tab, unlike "Manage devices":
 * it acts on the app's own session, and the browser usually has no web sign-in,
 * so the Custom Tab would first ask for the very password being changed.
 */
@Composable
fun ChangePasswordScreen(viewModel: ChangePasswordViewModel, onDone: () -> Unit) {
    val status by viewModel.status.collectAsStateWithLifecycle()

    Column(
        // Scrolls because the keyboard covers the Save button under three fields.
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val done = status.done
        if (done != null) {
            Done(done, onDone)
        } else {
            Form(status, viewModel)
        }
    }
}

@Composable
private fun Form(status: ChangePasswordStatus, viewModel: ChangePasswordViewModel) {
    PasswordField(
        value = viewModel.currentPassword,
        onValueChange = viewModel::onCurrentPasswordChange,
        label = "Current password",
        error = status.currentPasswordError,
    )

    val newProblem = viewModel.newPasswordProblem
    PasswordField(
        value = viewModel.newPassword,
        onValueChange = viewModel::onNewPasswordChange,
        label = "New password",
        error = newProblem?.takeIf { it != ChangePasswordViewModel.NEW_PASSWORD_HINT },
        hint = ChangePasswordViewModel.NEW_PASSWORD_HINT,
        modifier = Modifier.padding(top = 8.dp),
    )

    PasswordField(
        value = viewModel.confirmPassword,
        onValueChange = viewModel::onConfirmPasswordChange,
        label = "Confirm new password",
        error = viewModel.confirmPasswordProblem,
        modifier = Modifier.padding(top = 8.dp),
    )

    Text(
        OTHER_SESSIONS_NOTE,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    ErrorBanner(status.error, modifier = Modifier.padding(top = 8.dp))

    Button(
        onClick = viewModel::save,
        enabled = viewModel.formValid && !status.saving,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        if (status.saving) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Text("Saving", modifier = Modifier.padding(start = 10.dp))
        } else {
            Text("Save new password")
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val supporting = error ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun Done(message: String, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = PapSuccess,
            modifier = Modifier.size(22.dp),
        )
        Text(message, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
    }
    Text(
        OTHER_SESSIONS_NOTE,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("Done") }
}

/**
 * Said on both sides of the change. Sessions are not bound to the password, so
 * someone changing a leaked one must not believe it also signed the other person out.
 */
private const val OTHER_SESSIONS_NOTE = "Your other devices and browsers stay signed in."
