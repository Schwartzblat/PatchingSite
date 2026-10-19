package site.androidmod.store.ui.generic

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import site.androidmod.store.generic.WorksWithEntry
import site.androidmod.store.ui.AppIcon
import site.androidmod.store.ui.move.MainButton
import site.androidmod.store.ui.theme.PapSuccess

/** The apps we've tested on a real phone, from the status the Apps tab loaded; then the way to the picker. */
@Composable
fun WorksWithScreen(entries: List<WorksWithEntry>, onChoose: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        items(entries, key = { it.packageName }) { entry -> WorksWithCard(entry) }
        item(key = "others") {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Others may work too", style = MaterialTheme.typography.bodyMedium)
                MainButton("Choose an app", onClick = onChoose)
            }
        }
    }
}

@Composable
private fun WorksWithCard(entry: WorksWithEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(packageName = entry.packageName, name = entry.name, size = 44.dp)
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    StatusChip("Tested", PapSuccess, modifier = Modifier.padding(start = 8.dp))
                }
                Text(
                    entry.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
