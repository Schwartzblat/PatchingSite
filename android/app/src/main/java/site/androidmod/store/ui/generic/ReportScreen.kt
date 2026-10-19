package site.androidmod.store.ui.generic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import site.androidmod.store.R
import site.androidmod.store.generic.GenericRecord
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.LoadingState
import site.androidmod.store.ui.theme.PapSuccess

private sealed interface RecordLookup {
    data object Loading : RecordLookup
    data class Found(val record: GenericRecord?) : RecordLookup
}

/**
 * The report screen of [packageName]'s generic patch. Its record is read off the main thread by
 * [lookup], then [create] makes the view model. Nothing here reaches the network until Send.
 */
@Composable
fun ReportRoute(
    packageName: String,
    lookup: (String) -> GenericRecord?,
    create: (GenericRecord) -> ReportViewModel,
    onDone: () -> Unit,
) {
    val looked by produceState<RecordLookup>(RecordLookup.Loading, packageName) {
        value = RecordLookup.Found(
            withContext(Dispatchers.IO) {
                try {
                    lookup(packageName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    null
                }
            },
        )
    }
    when (val result = looked) {
        RecordLookup.Loading -> LoadingState()
        is RecordLookup.Found -> {
            val record = result.record
            if (record == null) {
                Text(
                    "This app isn't in the Library any more.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                ReportScreen(viewModel = viewModel { create(record) }, onDone = onDone)
            }
        }
    }
}

/** "Report how it works": the customer's choice, an optional note, and exactly what Send posts. */
@Composable
fun ReportScreen(viewModel: ReportViewModel, onDone: () -> Unit) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    Column(
        // Scrolls: the keyboard takes half the screen, with the preview and Send under the note.
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val thanks = status.sent
        if (thanks != null) {
            Sent(thanks, onDone)
        } else {
            Form(status, viewModel)
        }
    }
}

@Composable
private fun Form(status: ReportStatus, viewModel: ReportViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        InstalledAppIcon(viewModel.packageName, viewModel.label)
        Text(viewModel.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
    }

    Column(modifier = Modifier.selectableGroup().padding(top = 12.dp)) {
        Choice("It works", selected = viewModel.worked == true) { viewModel.onWorked(true) }
        Choice("It doesn't work", selected = viewModel.worked == false) { viewModel.onWorked(false) }
    }

    val length = viewModel.noteLength
    val tooLong = length > ReportViewModel.MAX_NOTE
    OutlinedTextField(
        value = viewModel.note,
        onValueChange = viewModel::onNoteChange,
        label = { Text("Note (optional)") },
        minLines = 3,
        isError = tooLong,
        supportingText = {
            Text(
                if (tooLong) {
                    "Too long: ${ReportViewModel.MAX_NOTE} characters at most ($length)"
                } else {
                    "$length / ${ReportViewModel.MAX_NOTE}"
                },
            )
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )

    val preview = viewModel.preview
    if (preview.isNotEmpty()) {
        Text(
            "This is what will be sent:",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                preview.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    Text(
        "Your account isn't stored with the report.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    ErrorBanner(status.error, modifier = Modifier.padding(top = 8.dp))

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
            Text("Send")
        }
    }
}

/** One of the two choices: the whole row is the tap target. */
@Composable
private fun Choice(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
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
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("Done") }
}
