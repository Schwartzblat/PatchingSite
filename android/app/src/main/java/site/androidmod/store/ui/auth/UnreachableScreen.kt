package site.androidmod.store.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import site.androidmod.store.R
import java.net.URI

/**
 * Launch with a stored session and no server: no network, or the server mid-
 * restart. The session is kept, so this is not the login form - that form used to
 * appear here with "Network error" on it, which reads as "you were signed out".
 *
 * Retry runs the same restore as launch. Sign out is the way off this screen for
 * someone whose server keeps failing for their session in particular - otherwise
 * clearing the app's data would be the only exit.
 */
@Composable
fun UnreachableScreen(
    host: String,
    detail: String,
    retrying: Boolean,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        // Outside the signed-in Scaffold, so it insets itself like the login form.
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_alert),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            "Can't reach $host",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "You're still signed in. Check your connection, then try again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        // The underlying reason in small print: "Request failed (502)" is what
        // tells the owner a user is looking at a server fault, not a dead network.
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onRetry,
            enabled = !retrying,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            if (retrying) {
                // Sized like the login button's, for the same reason: the default
                // indicator is taller than the button.
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text("Retry", modifier = Modifier.padding(start = 10.dp))
            } else {
                Text("Retry")
            }
        }
        TextButton(onClick = onSignOut, enabled = !retrying, modifier = Modifier.padding(top = 8.dp)) {
            Text("Sign out")
        }
    }
}

/** The host the app talks to, for "Can't reach <host>". Debug builds can point elsewhere. */
fun hostOf(baseUrl: String): String =
    runCatching { URI(baseUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: baseUrl
