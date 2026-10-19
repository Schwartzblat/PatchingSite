package site.androidmod.store.ui.generic

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import site.androidmod.store.ui.AppIcon

/** The Apps tab's way in, below the apps we build ourselves; shown only while generic patching is on. */
@Composable
fun GenericCard(onChoose: () -> Unit, onWorksWith: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Patch an app from your phone", style = MaterialTheme.typography.titleMedium)
            Text(
                "Remove the ads from an app you already have.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onChoose) { Text("Choose an app") }
                TextButton(onClick = onWorksWith, modifier = Modifier.padding(start = 4.dp)) {
                    Text("See which apps we've tested")
                }
            }
        }
    }
}

/**
 * An installed app's own icon, read from PackageManager off the main thread; the monogram
 * ([AppIcon]) until it loads, or when the app isn't installed.
 */
@Composable
internal fun InstalledAppIcon(packageName: String, label: String, size: Dp = 44.dp) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, packageName, pixels) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName).toBitmap(pixels, pixels).asImageBitmap()
            } catch (error: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
    val loaded = icon
    if (loaded == null) {
        AppIcon(packageName = packageName, name = label, size = size)
    } else {
        Image(loaded, contentDescription = null, modifier = Modifier.size(size).clip(MaterialTheme.shapes.small))
    }
}

/** A small coloured label: a picker badge, or Works-with's "Tested". */
@Composable
internal fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.16f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
