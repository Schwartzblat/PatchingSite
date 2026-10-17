package site.androidmod.store.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.androidmod.store.R
import site.androidmod.store.data.BuildVersion
import site.androidmod.store.download.DownloadKey
import site.androidmod.store.download.DownloadRegistry
import site.androidmod.store.download.DownloadState
import site.androidmod.store.install.InstalledApp
import site.androidmod.store.install.VersionOrder
import site.androidmod.store.install.Versions
import site.androidmod.store.ui.AppIcon
import site.androidmod.store.ui.EmptyState
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.LoadingState
import site.androidmod.store.ui.formatBytes
import site.androidmod.store.ui.library.InstallPermissionBanner
import site.androidmod.store.ui.library.LibraryViewModel
import site.androidmod.store.move.WHATSAPP_PACKAGE
import site.androidmod.store.ui.move.GoBackLink
import site.androidmod.store.ui.move.KeepChatsBeforeInstallDialog
import site.androidmod.store.ui.move.MoveViewModel
import site.androidmod.store.ui.move.WhatsAppMoveCard
import site.androidmod.store.ui.theme.PapSuccess
import site.androidmod.store.ui.theme.PapSuccessBg
import site.androidmod.store.ui.theme.PapWarning

@Composable
fun AppDetailScreen(
    packageName: String,
    viewModel: CatalogViewModel,
    libraryViewModel: LibraryViewModel,
    moveViewModel: MoveViewModel,
    onSubscribe: () -> Unit,
    onOpenMoveGuide: () -> Unit,
    onDownload: (BuildVersion) -> Unit,
) {
    val state by viewModel.versions.collectAsStateWithLifecycle()
    val app = viewModel.appFor(packageName)
    val downloads by DownloadRegistry.downloads.collectAsStateWithLifecycle()
    // Without this the Install button here is permanently enabled and every install
    // failure started from this screen is silent - the user would have to guess to go
    // and look at the Library tab.
    val libraryState by libraryViewModel.state.collectAsStateWithLifecycle()
    // The settings intent needs the Activity context, not the application one.
    val context = LocalContext.current
    val isWhatsApp = packageName == WHATSAPP_PACKAGE
    val moveGuide by moveViewModel.guide.collectAsStateWithLifecycle()
    // The build an Install tap was held back for, while "Keep your chats?" is asked.
    var heldInstall by remember { mutableStateOf<DownloadKey?>(null) }

    // An on-device app has no server builds to list; its updates come through the Library.
    val onDevice = app?.onDevice == true
    LaunchedEffect(packageName, onDevice) { if (!onDevice) viewModel.loadVersions(packageName) }

    // What is installed can change while this app is in the background, so it is
    // re-read on every return to the screen - the same reason LibraryScreen
    // refreshes on resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, packageName) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshInstalled(packageName)
                if (isWhatsApp) moveViewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Resuming is not enough on its own: the screen resumes as the system install
    // dialog closes, and the result lands seconds after that.
    LaunchedEffect(libraryState.installing) {
        if (libraryState.installing == null) {
            viewModel.refreshInstalled(packageName)
            if (isWhatsApp) moveViewModel.refresh()
        }
    }
    // Guarded by package: the state belongs to whichever app was loaded last, and
    // must never label this one with another app's version.
    val installed = state.installed?.takeIf { it.packageName == packageName }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // The app's name is the title in the app bar, so this header carries the
        // things the bar cannot: the artwork and the package id.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = packageName, name = app?.name ?: packageName, size = 44.dp)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InstalledStatus(installed = installed, latest = if (onDevice) null else state.versions.firstOrNull()?.version)
            }
        }

        // Only WhatsApp has chats worth carrying across the reinstall, and the card
        // only shows when there is something to do: see WhatsAppMoveCard.
        if (isWhatsApp) {
            WhatsAppMoveCard(moveGuide, onOpen = onOpenMoveGuide)
            GoBackLink(moveGuide, onOpen = {
                // From a previous build the guide would otherwise move into ours.
                moveViewModel.chooseGoingBack()
                onOpenMoveGuide()
            })
        }

        ErrorBanner(state.error)
        ErrorBanner(libraryState.error)
        if (libraryState.needsInstallPermission) {
            InstallPermissionBanner(onOpenSettings = { libraryViewModel.openInstallPermissionSettings(context) })
        }

        when {
            onDevice -> OnDevicePanel()
            state.loading && state.versions.isEmpty() -> LoadingState()
            state.versions.isEmpty() && state.error == null -> EmptyState(
                icon = R.drawable.ic_download,
                title = "No builds yet",
                message = "There is no patched build for this app right now. Check back soon.",
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(state.versions, key = { it.key }) { version ->
                    val key = DownloadKey(packageName, version.version)
                    VersionCard(
                        version = version,
                        isInstalled = installed?.versionName == version.version,
                        download = downloads[key],
                        installing = libraryState.installing,
                        // Compared as a whole key, not by version string: two packages
                        // can carry the same version, and only this one is installing.
                        isInstallingThis = libraryState.installing == key,
                        // Installing ours means the official WhatsApp has to go first, and
                        // with it every chat on the phone, so that is asked about before
                        // the install rather than discovered after it.
                        onInstall = {
                            if (isWhatsApp && moveGuide.officialAtRisk) heldInstall = key
                            else libraryViewModel.install(key)
                        },
                        onDownload = { onDownload(version) },
                        onSubscribe = onSubscribe,
                    )
                }
            }
        }
    }

    if (heldInstall != null) {
        KeepChatsBeforeInstallDialog(
            onKeep = {
                heldInstall = null
                onOpenMoveGuide()
            },
            // "Remove" is what it says: the uninstall dialog, after which Install works.
            onRemove = {
                heldInstall = null
                libraryViewModel.uninstall(context, WHATSAPP_PACKAGE)
            },
            onDismiss = { heldInstall = null },
        )
    }
}

/**
 * The same wording and colours as the Library card, so one app's installed state
 * reads the same on both screens. "up to date" is only claimed once there is a
 * build list to compare against.
 */
@Composable
private fun OnDevicePanel() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            "Built on your phone from the official app. Updates come through the Library.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun InstalledStatus(installed: InstalledApp?, latest: String?) {
    val order = if (installed != null && latest != null) {
        Versions.compare(latest, installed.versionName)
    } else VersionOrder.UNKNOWN
    val (status, tint) = when {
        installed == null -> "Not installed" to MaterialTheme.colorScheme.onSurfaceVariant
        order == VersionOrder.NEWER -> "Installed ${installed.versionName} · $latest available" to PapWarning
        order == VersionOrder.UNKNOWN -> "Installed ${installed.versionName}" to PapSuccess
        else -> "Installed ${installed.versionName} · up to date" to PapSuccess
    }
    Text(
        status,
        style = MaterialTheme.typography.bodySmall,
        color = tint,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun VersionCard(
    version: BuildVersion,
    isInstalled: Boolean,
    download: DownloadState?,
    installing: DownloadKey?,
    isInstallingThis: Boolean,
    onInstall: () -> Unit,
    onDownload: () -> Unit,
    onSubscribe: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(version.version, style = MaterialTheme.typography.titleMedium)
                        if (isInstalled) InstalledTag()
                    }
                    Text(
                        "${formatBytes(version.size)} · ${version.type.uppercase()} · ${version.updatedAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                VersionAction(
                    download = download,
                    installing = installing != null,
                    isInstallingThis = isInstallingThis,
                    onInstall = onInstall,
                    onDownload = onDownload,
                    onSubscribe = onSubscribe,
                )
            }

            if (download is DownloadState.Transferring && download.total > 0) {
                LinearProgressIndicator(
                    progress = { download.bytesRead.toFloat() / download.total },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                )
            }
            if (download is DownloadState.Failed) {
                ErrorBanner(download.message)
            }
        }
    }
}

/**
 * The button's job changes with the download's state; the states themselves are
 * unchanged from the version this replaces. A 402 is not a retryable failure -
 * retrying gets the same 402 forever - so that one branch offers the fix instead.
 */
@Composable
private fun VersionAction(
    download: DownloadState?,
    installing: Boolean,
    isInstallingThis: Boolean,
    onInstall: () -> Unit,
    onDownload: () -> Unit,
    onSubscribe: () -> Unit,
) {
    when (download) {
        is DownloadState.Queued -> PendingLabel("Queued…")
        is DownloadState.Preparing -> PendingLabel(download.message ?: "Preparing…")
        is DownloadState.Transferring -> PendingLabel(
            if (download.total > 0) {
                "${download.bytesRead * 100 / download.total}%"
            } else {
                formatBytes(download.bytesRead)
            }
        )
        is DownloadState.Done -> Button(onClick = onInstall, enabled = !installing) {
            Text(if (isInstallingThis) "Installing…" else "Install")
        }
        is DownloadState.Failed -> if (download.needsSubscription) {
            Button(onClick = onSubscribe) { Text("Subscribe") }
        } else {
            Button(onClick = onDownload) { Text("Retry") }
        }
        else -> Button(onClick = onDownload) {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Text("Download", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Marks the build that is on the device now, so it can be told apart from the rest. */
@Composable
private fun InstalledTag() {
    Surface(
        modifier = Modifier.padding(start = 8.dp),
        shape = MaterialTheme.shapes.small,
        color = PapSuccessBg,
    ) {
        Text(
            "Installed",
            style = MaterialTheme.typography.labelSmall,
            color = PapSuccess,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun PendingLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
