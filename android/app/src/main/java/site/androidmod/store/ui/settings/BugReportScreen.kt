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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.androidmod.store.R
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.emailSupport
import site.androidmod.store.ui.theme.PapSuccess

/**
 * "Report a bug", reached from Settings. The title and back arrow are the
 * Scaffold's, like every other signed-in screen.
 */
@Composable
fun BugReportScreen(
    viewModel: BugReportViewModel,
    deviceContext: Map<String, String>,
    onDone: () -> Unit,
) {
    val status by viewModel.status.collectAsStateWithLifecycle()

    Column(
        // Scrolls because the keyboard takes half the screen and the Send button
        // sits under a six-line field.
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val thanks = status.sent
        if (thanks != null) {
            Sent(thanks, onDone)
        } else {
            Form(status, viewModel, deviceContext)
        }
    }
}

@Composable
private fun Form(status: BugReportStatus, viewModel: BugReportViewModel, deviceContext: Map<String, String>) {
    val context = LocalContext.current
    Text(
        "What happened, and what did you expect instead? Steps to reproduce it help most.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val length = viewModel.messageLength
    OutlinedTextField(
        value = viewModel.message,
        onValueChange = viewModel::onMessageChange,
        label = { Text("Describe the problem") },
        minLines = 6,
        isError = length > BugReportViewModel.MAX_MESSAGE,
        supportingText = {
            Text(
                when {
                    length > BugReportViewModel.MAX_MESSAGE ->
                        "Too long: ${BugReportViewModel.MAX_MESSAGE} characters at most ($length)"
                    length in 1 until BugReportViewModel.MIN_MESSAGE ->
                        "At least ${BugReportViewModel.MIN_MESSAGE} characters"
                    else -> "$length / ${BugReportViewModel.MAX_MESSAGE}"
                }
            )
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    )

    OutlinedTextField(
        value = viewModel.email,
        onValueChange = viewModel::onEmailChange,
        label = { Text("Email (optional)") },
        isError = viewModel.emailInvalid,
        supportingText = {
            Text(if (viewModel.emailInvalid) "This doesn't look like an email address." else "Where we can reply to you.")
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )

    // Said, not hidden: the report carries this whether or not the user reads
    // it, and they should know what leaves their phone.
    Text(
        "Sent with your report: Patched Store ${deviceContext["app_version"].orEmpty()} · " +
            "${deviceContext["os"].orEmpty()} · ${deviceContext["device"].orEmpty()}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    ErrorBanner(status.error, modifier = Modifier.padding(top = 8.dp))
    if (status.error != null) {
        // Whatever stopped the form - the hourly limit, the server, the
        // network - email still reaches the same inbox.
        OutlinedButton(
            onClick = { emailSupport(context, supportEmailBody(deviceContext, viewModel.message)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Email support instead") }
    }

    Button(
        onClick = viewModel::send,
        enabled = viewModel.formValid && !status.sending,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        if (status.sending) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Text("Sending", modifier = Modifier.padding(start = 10.dp))
        } else {
            Text("Send report")
        }
    }
}

@Composable
private fun Sent(thanks: String, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = PapSuccess,
            modifier = Modifier.size(22.dp),
        )
        Text(thanks, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
    }
    Text(
        "If you left an email address, a reply will go there.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("Done") }
}

/**
 * The prefilled body of a support email: whatever the user already typed, then
 * the same context a bug report carries, so support does not have to ask.
 */
fun supportEmailBody(deviceContext: Map<String, String>, message: String = ""): String = buildString {
    // With nothing typed yet the body opens on blank lines, leaving the cursor
    // above the context for the user to write in.
    if (message.isNotBlank()) append(message.trim())
    append("\n\n---\n")
    append("Patched Store ").append(deviceContext["app_version"].orEmpty()).append('\n')
    append(deviceContext["os"].orEmpty()).append('\n')
    append(deviceContext["device"].orEmpty())
}
