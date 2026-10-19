package site.androidmod.store.ui.generic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.androidmod.store.R
import site.androidmod.store.generic.PickerBadge
import site.androidmod.store.generic.PickerRow
import site.androidmod.store.ui.EmptyState
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.LoadingState
import site.androidmod.store.ui.theme.PapAccentStrong
import site.androidmod.store.ui.theme.PapSuccess
import site.androidmod.store.ui.theme.PapWarning

/**
 * The phone's apps, searchable, each with its badge. Tested and Not tested go on to confirm; our
 * store's own apps open their store page; the rest are greyed, with their reasons under them.
 */
@Composable
fun PickerScreen(viewModel: PickerViewModel, onStorePage: (String) -> Unit, onConfirm: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        val error = state.error
        when {
            error != null -> ErrorBanner(error)
            state.loading -> LoadingState()
            else -> {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQuery,
                    placeholder = { Text("Search your apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (state.rows.isEmpty()) {
                    EmptyState(
                        icon = R.drawable.ic_apps,
                        title = "No apps found",
                        message = if (state.query.isBlank()) "Apps you've installed show here." else "No app matches your search.",
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        items(state.rows, key = { it.app.packageName }) { row ->
                            PickerRowCard(row) {
                                when (val tap = pickerTap(row)) {
                                    is PickerTap.StorePage -> onStorePage(tap.packageName)
                                    is PickerTap.Confirm -> onConfirm(tap.packageName)
                                    PickerTap.None -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRowCard(row: PickerRow, onClick: () -> Unit) {
    val tappable = pickerTap(row) != PickerTap.None
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = tappable, onClick = onClick)
            // Greyed: it can't be picked, and the reasons below say why.
            .alpha(if (tappable || row.badge == PickerBadge.Checking) 1f else 0.6f),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InstalledAppIcon(row.app.packageName, row.app.label)
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(row.app.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        row.app.versionName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(pickerBadgeText(row.badge), badgeColor(row.badge), modifier = Modifier.padding(start = 8.dp))
            }
            (row.badge as? PickerBadge.CantPatch)?.reasons?.forEach { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun badgeColor(badge: PickerBadge): Color = when (badge) {
    PickerBadge.Tested -> PapSuccess
    PickerBadge.InOurStore -> PapAccentStrong
    is PickerBadge.CantPatch -> PapWarning
    PickerBadge.NotTested, PickerBadge.AlreadyPatched, PickerBadge.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
}
