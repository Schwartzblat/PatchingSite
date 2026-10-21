package site.androidmod.store.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import site.androidmod.store.R
import site.androidmod.store.data.NotEnoughSpaceException
import site.androidmod.store.ui.theme.PapAccentSoft
import site.androidmod.store.ui.theme.PapAccentStrong
import java.util.Locale

/**
 * The website's app artwork, bundled.
 *
 * GET /api/apps deliberately serves no icon URLs - the server comment says the
 * Android client brings its own - so these are the same files `client/public`
 * serves. Keyed by package name rather than by the catalogue's `key` because
 * LibraryEntry carries a package and no key, and this way one map serves both
 * screens. An unfamiliar package (a new app shipped server-side before the client
 * catches up) falls back to a monogram rather than a blank square.
 */
private val BundledIcons = mapOf(
    "com.whatsapp" to R.drawable.app_whatsapp,
)

@Composable
fun AppIcon(packageName: String, name: String, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    val shape = MaterialTheme.shapes.small
    val drawable = BundledIcons[packageName]
    Box(
        modifier = modifier.size(size).clip(shape).background(PapAccentSoft),
        contentAlignment = Alignment.Center,
    ) {
        if (drawable != null) {
            Image(
                painter = painterResource(drawable),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = PapAccentStrong,
            )
        }
    }
}

/**
 * One app bar for every screen, so titles stop being body text inside a scrolling
 * column. Transparent because the Scaffold already paints the background and a
 * tinted bar would draw a seam under the status bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreTopBar(title: String, onBack: (() -> Unit)? = null) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

/** One error surface for the whole app, so failures never look different per screen. */
@Composable
fun ErrorBanner(message: String?, modifier: Modifier = Modifier) {
    if (message.isNullOrBlank()) return
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_alert),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                modifier = Modifier.padding(start = 10.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * [ErrorBanner]'s counterpart for news that is not a failure, such as "Account
 * created". Same shape and place, so the two read as one system; green check
 * rather than red alert, with body text in the ordinary colour because a whole
 * paragraph in green is harder to read than it is reassuring.
 */
@Composable
fun NoticeBanner(message: String?, modifier: Modifier = Modifier) {
    if (message.isNullOrBlank()) return
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                modifier = Modifier.padding(start = 10.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Shown when a list has loaded and is genuinely empty. Previously these were a bare
 * line of body text left-aligned under the title, which read as a rendering failure
 * rather than as an answer.
 */
@Composable
fun EmptyState(icon: Int, title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** The one spinner, centred, for a list that has nothing to show yet. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    // Locale.US, not the device default: every other word on screen is English,
    // so a German default rendering "1,5 MB" - or an Arabic one switching digit
    // shapes - would be the only localised thing in the sentence.
    return if (value >= 10) "${value.toInt()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

/**
 * The one wording for a download the device has no room for, shared by patched
 * builds (DownloadService) and the store's own update (UpdateViewModel). It names
 * the amount when it is known, because "free up some space" alone leaves the user
 * guessing whether one photo or half the phone has to go.
 */
fun notEnoughSpaceMessage(error: NotEnoughSpaceException): String {
    val amount = if (error.bytesNeeded > 0) formatBytes(error.bytesNeeded) else "some space"
    return "There isn't enough free space for this download. Free up $amount and try again."
}
