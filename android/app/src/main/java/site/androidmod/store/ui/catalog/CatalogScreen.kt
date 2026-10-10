package site.androidmod.store.ui.catalog

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.androidmod.store.R
import site.androidmod.store.data.CatalogApp
import site.androidmod.store.ui.AppIcon
import site.androidmod.store.ui.EmptyState
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.LoadingState
import site.androidmod.store.ui.move.MoveBanner
import site.androidmod.store.ui.move.MoveViewModel
import site.androidmod.store.ui.update.UpdateBanner
import site.androidmod.store.update.UpdateViewModel

@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    updateViewModel: UpdateViewModel,
    moveViewModel: MoveViewModel,
    onSubscribe: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onOpenMoveGuide: () -> Unit,
    onOpen: (CatalogApp) -> Unit,
) {
    val state by viewModel.apps.collectAsStateWithLifecycle()
    val update by updateViewModel.state.collectAsStateWithLifecycle()
    val moveGuide by moveViewModel.guide.collectAsStateWithLifecycle()

    // A half-done chat move is the one thing here that changes in other apps (the
    // uninstaller, Settings), so it is re-read on every return, not only on entry.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) moveViewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Re-read on every entry to the tab: NavHost disposes this screen when another
    // tab is showing, so returning from a successful checkout re-runs this and the
    // banner goes away.
    LaunchedEffect(Unit) { viewModel.loadSubscription() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ErrorBanner(state.error)

        // The Apps tab is where the app opens, so this is the one place an update
        // is offered unasked. Renders nothing while the store is up to date.
        UpdateBanner(update, onUpdate = updateViewModel::update, onOpenInstallSettings = onOpenInstallSettings)

        // The store opens here, and mid-move WhatsApp may already be gone, so this is
        // how the way back to the guide is found. Nothing while no move is under way.
        MoveBanner(moveGuide, onOpen = onOpenMoveGuide)

        // `false`, not `!= true`: while the status is still unknown the banner
        // stays hidden rather than accusing a paying subscriber of not paying.
        if (state.subscribed == false) SubscriptionBanner(onSubscribe)

        when {
            state.loading && state.apps.isEmpty() -> LoadingState()
            state.apps.isEmpty() && state.error == null -> EmptyState(
                icon = R.drawable.ic_apps,
                title = "No apps yet",
                message = "The catalogue is empty. Check back soon.",
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(state.apps, key = { it.packageName }) { app ->
                    AppCard(app = app, onClick = { onOpen(app) })
                }
            }
        }
    }
}

@Composable
private fun AppCard(app: CatalogApp, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(packageName = app.packageName, name = app.name)
                Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (app.features.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 14.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                app.features.forEach { feature ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            feature,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The catalogue is browsable without a subscription; downloading is not. Saying so
 * here - with the button that fixes it - beats letting the user pick a build and
 * meet a 402 on the detail screen.
 */
@Composable
private fun SubscriptionBanner(onSubscribe: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Subscription required",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "Browsing is free. Downloads are not.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onSubscribe, modifier = Modifier.padding(start = 12.dp)) {
                Text("Subscribe")
            }
        }
    }
}
