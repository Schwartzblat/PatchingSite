package site.androidmod.store.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.androidmod.store.BuildConfig
import site.androidmod.store.R
import site.androidmod.store.StoreApp
import site.androidmod.store.patch.formatSha1
import site.androidmod.store.ui.SiteLinks
import site.androidmod.store.ui.auth.AuthViewModel
import site.androidmod.store.ui.emailSupport
import site.androidmod.store.ui.move.MoveViewModel
import site.androidmod.store.ui.openLink
import site.androidmod.store.ui.update.AppVersionContent
import site.androidmod.store.update.UpdateViewModel

@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel,
    updateViewModel: UpdateViewModel,
    moveViewModel: MoveViewModel,
    onOpenInstallSettings: () -> Unit,
    onReportBug: () -> Unit,
    onChangePassword: () -> Unit,
    onOpenMoveGuide: () -> Unit,
) {
    val container = StoreApp.instance.container
    val update by updateViewModel.state.collectAsStateWithLifecycle()
    val moveGuide by moveViewModel.guide.collectAsStateWithLifecycle()
    // Read on the way in: WhatsApp may have been installed or removed since another
    // screen last looked, and the row below depends on which one is there.
    LaunchedEffect(moveViewModel) { moveViewModel.refresh() }
    // The Activity's context: a Custom Tab or a mail app started from the
    // application context is a background launch and may not appear.
    val context = LocalContext.current
    // Fetched on the way in, off the main thread; any failure just leaves the row out.
    val signingSha1 by produceState<String?>(null, container) {
        value = if (!container.auth.isSignedIn) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    formatSha1(container.signingKeys.get().certSha1)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    null
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Debug-only: the base URL is the host the app trusts for installable code,
        // so a release build ships pinned to BuildConfig.DEFAULT_BASE_URL and never
        // offers this field. BuildConfig.DEBUG is a compile-time constant, so this
        // branch is stable across recompositions and the remember below is safe.
        if (BuildConfig.DEBUG) {
            var baseUrl by remember { mutableStateOf(container.settings.baseUrl) }
            SettingsSection(title = "Server") {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    supportingText = { Text("Takes effect on the next request.") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.padding(top = 12.dp)) {
                    Button(onClick = { container.settings.baseUrl = baseUrl }) { Text("Save") }
                    OutlinedButton(
                        onClick = {
                            container.settings.resetBaseUrl()
                            baseUrl = container.settings.baseUrl
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Reset") }
                }
            }
        }

        // Debug-only as well: the root installer is unit-tested but has never
        // completed an install on a device, so release builds do not offer it and
        // Container.installer() ignores the preference there.
        if (BuildConfig.DEBUG) SilentInstallSection()

        SettingsSection(title = "App version") {
            AppVersionContent(
                state = update,
                onCheck = updateViewModel::check,
                onUpdate = updateViewModel::update,
                onOpenInstallSettings = onOpenInstallSettings,
            )
        }

        // The website pages open in a Custom Tab rather than being rebuilt here:
        // they are the same pages the site links to, kept in one place, and the
        // devices page needs the web session only a browser has. Changing the
        // password is the exception, native on purpose: see ChangePasswordScreen.
        LinkSection(title = "Account") {
            LinkRow(title = "Change password", onClick = onChangePassword)
            LinkRow(
                title = "Manage devices",
                subtitle = "See or reset the phones your apps are registered on",
                onClick = { openLink(context, SiteLinks.MANAGE_DEVICES) },
            )
        }

        signingSha1?.let { sha1 ->
            LinkSection(title = "Signing key") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Signing key SHA-1", style = MaterialTheme.typography.titleSmall)
                    Text(
                        sha1,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        "Needed for Google Drive backup setup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        LinkSection(title = "Help") {
            LinkRow(
                title = "How to install",
                subtitle = "Permissions, Play Protect and other first-install questions",
                onClick = { openLink(context, SiteLinks.INSTALL_HELP) },
            )
            // Only while it applies: ours (or a previous build) installed with nothing moved,
            // or a return under way.
            if (moveGuide.offersReturn) {
                LinkRow(
                    title = "Go back to the regular WhatsApp",
                    subtitle = "Keep your chats when you switch",
                    onClick = {
                        // From a previous build the guide would otherwise move into ours.
                        moveViewModel.chooseGoingBack()
                        onOpenMoveGuide()
                    },
                )
            }
            LinkRow(title = "Report a bug", subtitle = "Tell us what went wrong", onClick = onReportBug)
            LinkRow(
                title = "Contact support",
                subtitle = SiteLinks.SUPPORT_EMAIL,
                onClick = { emailSupport(context, supportEmailBody(container.deviceContext())) },
            )
        }

        LinkSection(title = "Legal") {
            LinkRow(title = "Terms of service", onClick = { openLink(context, SiteLinks.TERMS) })
            LinkRow(title = "Privacy policy", onClick = { openLink(context, SiteLinks.PRIVACY) })
            LinkRow(title = "Refund policy", onClick = { openLink(context, SiteLinks.REFUNDS) })
        }

        OutlinedButton(
            onClick = { authViewModel.signOut() },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
        ) { Text("Sign out") }
    }
}

/**
 * The silent-install switch. Root is probed when the user turns it ON, never
 * before: probing runs `su`, and on a rooted phone the superuser manager answers
 * an app's first `su` with a grant prompt. This screen used to probe as soon as it
 * opened, so merely visiting Settings popped a Magisk dialog.
 */
@Composable
private fun SilentInstallSection() {
    val container = StoreApp.instance.container
    var enabled by remember { mutableStateOf(container.settings.silentInstall) }
    var probing by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SettingsSection(title = "Installing (debug builds)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Silent install", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        probing -> "Asking for root..."
                        // RootInstaller caches its answer for the process, so a
                        // grant given after a refusal needs a restart to be seen.
                        refused -> "Unavailable: this device did not grant root to this app. " +
                            "Restart the app after granting it."
                        else -> "Install without the system dialog, using root. Untested on a device."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(
                checked = enabled,
                enabled = !probing,
                onCheckedChange = { on ->
                    if (!on) {
                        enabled = false
                        container.settings.silentInstall = false
                    } else {
                        probing = true
                        scope.launch {
                            // isAvailable runs `su`, which must not happen on the main thread.
                            val granted = withContext(Dispatchers.IO) { container.rootInstaller.isAvailable }
                            probing = false
                            refused = !granted
                            enabled = granted
                            container.settings.silentInstall = granted
                        }
                    }
                },
            )
        }
    }
}

/**
 * A card of tappable rows. Its own shape rather than [SettingsSection] because
 * that one pads its content, and a row's ripple should reach the card's edges.
 */
@Composable
private fun LinkSection(title: String, rows: @Composable () -> Unit) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) { rows() }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp).size(18.dp),
        )
    }
}

/** A labelled card, so the screen reads as grouped settings rather than a stack of controls. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}
