package site.androidmod.store.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import site.androidmod.store.R
import site.androidmod.store.StoreApp
import site.androidmod.store.download.DownloadService
import site.androidmod.store.ui.auth.AuthViewModel
import site.androidmod.store.ui.auth.LoginScreen
import site.androidmod.store.ui.auth.RegisterScreen
import site.androidmod.store.ui.auth.UnreachableScreen
import site.androidmod.store.ui.auth.hostOf
import site.androidmod.store.ui.billing.SubscriptionScreen
import site.androidmod.store.ui.catalog.AppDetailScreen
import site.androidmod.store.ui.catalog.CatalogScreen
import site.androidmod.store.ui.catalog.CatalogViewModel
import site.androidmod.store.ui.library.LibraryScreen
import site.androidmod.store.ui.library.LibraryViewModel
import site.androidmod.store.ui.move.MoveChatsScreen
import site.androidmod.store.ui.move.MoveViewModel
import site.androidmod.store.ui.patch.PatchQuestionHost
import site.androidmod.store.ui.settings.BugReportScreen
import site.androidmod.store.ui.settings.BugReportViewModel
import site.androidmod.store.ui.settings.ChangePasswordScreen
import site.androidmod.store.ui.settings.SettingsScreen
import site.androidmod.store.ui.theme.PapAccentSoft
import site.androidmod.store.update.UpdateViewModel

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val CATALOG = "catalog"
    const val APP_DETAIL = "app/{package}"
    const val LIBRARY = "library"
    const val SUBSCRIPTION = "subscription"
    const val SETTINGS = "settings"
    const val BUG_REPORT = "settings/bug-report"
    const val CHANGE_PASSWORD = "settings/change-password"
    const val MOVE_CHATS = "move-chats"

    fun appDetail(packageName: String) = "app/$packageName"
}

/** Screens pushed on top of a tab: they get a back arrow instead of a tab of their own. */
private val PUSHED_ROUTES = setOf(Routes.APP_DETAIL, Routes.BUG_REPORT, Routes.CHANGE_PASSWORD, Routes.MOVE_CHATS)

/** The bottom bar's contents, in order. */
private val Tabs = listOf(
    Triple(Routes.CATALOG, "Apps", R.drawable.ic_apps),
    Triple(Routes.LIBRARY, "Library", R.drawable.ic_library),
    Triple(Routes.SUBSCRIPTION, "Plan", R.drawable.ic_plan),
    Triple(Routes.SETTINGS, "Settings", R.drawable.ic_settings),
)

@Composable
fun AppNavigation() {
    val authViewModel: AuthViewModel = viewModel()
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    // A stored session the server could not be asked about. Checked before the
    // spinner below, which would otherwise replace this screen for the length of
    // every retry.
    authState.unreachable?.let { detail ->
        UnreachableScreen(
            host = remember { hostOf(StoreApp.instance.container.settings.baseUrl) },
            detail = detail,
            retrying = authState.loading,
            onRetry = authViewModel::restore,
            onSignOut = authViewModel::signOut,
        )
        return
    }

    // The launch restore: show a spinner rather than flashing the login screen at
    // someone who is already signed in. Only the restore - a sign-in in flight
    // keeps its form, which this branch would discard (see AuthState.restoring).
    if (authState.restoring) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (authState.user == null) {
        // No Scaffold on this branch, so these two screens inset themselves; without
        // it the sign-in form runs under the status bar in edge-to-edge.
        NavHost(
            navController = navController,
            startDestination = Routes.LOGIN,
            modifier = Modifier.safeDrawingPadding(),
        ) {
            // The view model is shared and outlives navigation, so its error has to be
            // dropped when crossing between these two screens. Otherwise a failed
            // sign-in shows its banner on the registration form, and a rejected
            // registration shows its banner on the login form.
            composable(Routes.LOGIN) {
                LoginScreen(
                    authViewModel,
                    onRegister = {
                        authViewModel.clearError()
                        navController.navigate(Routes.REGISTER)
                    },
                )
            }
            composable(Routes.REGISTER) {
                RegisterScreen(
                    viewModel = authViewModel,
                    onRegistered = { navController.popBackStack(Routes.LOGIN, inclusive = false) },
                    onBack = {
                        authViewModel.clearError()
                        navController.popBackStack()
                    },
                )
            }
        }
        return
    }

    val catalogViewModel: CatalogViewModel = viewModel()
    val libraryViewModel: LibraryViewModel = viewModel()
    // Here, not per screen, so the Apps banner and Settings share one update: one
    // started from either shows its progress in both. Checks once, on creation.
    val updateViewModel: UpdateViewModel = viewModel()
    // Here for the same reason: the WhatsApp page card, the Apps banner, the Library
    // warnings and the guide all show one move, and must never disagree about it.
    val moveViewModel: MoveViewModel = viewModel()
    val catalogState by catalogViewModel.apps.collectAsStateWithLifecycle()

    // Collected here rather than in a screen: an install started from app detail can
    // finish after the user has moved to another tab, and must still be announced.
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(libraryViewModel) {
        libraryViewModel.installResults.collect { text ->
            Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
        }
    }

    // One way to reach a tab, shared by the bottom bar and by the "Subscribe"
    // affordances on the catalogue and app-detail screens, so a paywalled download
    // lands on the Plan tab the same way tapping Plan does.
    val openTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            launchSingleTop = true
            popUpTo(Routes.CATALOG)
        }
    }

    // The store's own update needs the same "install unknown apps" grant as a
    // patched app, so both routes to the system toggle go through one place.
    val activityContext = LocalContext.current
    val openInstallSettings = { libraryViewModel.openInstallPermissionSettings(activityContext) }

    // launchSingleTop so a double tap on Start cannot stack two guides. Every way in
    // marks the start, which is what a backup has to be newer than.
    val openMoveGuide = {
        moveViewModel.markStarted()
        navController.navigate(Routes.MOVE_CHATS) { launchSingleTop = true }
    }

    // A notification tap or the patched app's update button. Reaching here means signed
    // in (the signed-out host returned above), so one that arrived earlier has waited.
    val request by NavigationRequests.pending.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        when (val pending = request ?: return@LaunchedEffect) {
            is NavRequest.Update -> {
                NavigationRequests.consume()
                openTab(Routes.LIBRARY)
                libraryViewModel.handleUpdate(pending.pkg)
            }
            NavRequest.Move -> {
                NavigationRequests.consume()
                openMoveGuide()
            }
        }
    }

    // The features-off question of a patch job, answerable on any screen (and right after
    // sign-in), which is where the "Open the app to continue" notification lands.
    PatchQuestionHost()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // App detail is pushed on top of the catalogue rather than being a tab of its
    // own, so it keeps "Apps" lit instead of lighting nothing. The bug report and
    // change-password forms are pushed on top of Settings the same way, and the
    // chat-move guide - which belongs to the WhatsApp page wherever it was opened
    // from - on top of Apps.
    val selectedRoute = when (currentRoute) {
        Routes.APP_DETAIL, Routes.MOVE_CHATS -> Routes.CATALOG
        Routes.BUG_REPORT, Routes.CHANGE_PASSWORD -> Routes.SETTINGS
        else -> currentRoute
    }

    // The title lives here rather than inside each screen so there is exactly one
    // app bar in the tree and the Scaffold applies status-bar insets once. Reading
    // the catalogue as state matters: resolving the detail title through the view
    // model alone would leave the package name showing if the screen is opened
    // before the app list has landed.
    val title = when (currentRoute) {
        Routes.LIBRARY -> "Library"
        Routes.SUBSCRIPTION -> "Subscription"
        Routes.SETTINGS -> "Settings"
        Routes.BUG_REPORT -> "Report a bug"
        Routes.CHANGE_PASSWORD -> "Change password"
        Routes.MOVE_CHATS -> "Move your chats"
        Routes.APP_DETAIL -> {
            val pkg = backStackEntry?.arguments?.getString("package").orEmpty()
            catalogState.apps.firstOrNull { it.packageName == pkg }?.name ?: pkg
        }
        else -> "Patched Store"
    }

    Scaffold(
        topBar = {
            StoreTopBar(
                title = title,
                onBack = if (currentRoute in PUSHED_ROUTES) {
                    { navController.popBackStack() }
                } else {
                    null
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tabs.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = selectedRoute == route,
                        onClick = { openTab(route) },
                        icon = { Icon(painterResource(icon), contentDescription = null) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = PapAccentSoft,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CATALOG,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.CATALOG) {
                CatalogScreen(
                    viewModel = catalogViewModel,
                    updateViewModel = updateViewModel,
                    moveViewModel = moveViewModel,
                    onSubscribe = { openTab(Routes.SUBSCRIPTION) },
                    onOpenInstallSettings = openInstallSettings,
                    onOpenMoveGuide = openMoveGuide,
                ) { app ->
                    navController.navigate(Routes.appDetail(app.packageName))
                }
            }
            composable(Routes.APP_DETAIL) { entry ->
                val context = LocalContext.current
                val pkg = entry.arguments?.getString("package").orEmpty()
                AppDetailScreen(
                    packageName = pkg,
                    viewModel = catalogViewModel,
                    libraryViewModel = libraryViewModel,
                    moveViewModel = moveViewModel,
                    onSubscribe = { openTab(Routes.SUBSCRIPTION) },
                    onOpenMoveGuide = openMoveGuide,
                    onDownload = { version ->
                        DownloadService.start(
                            context, pkg, version.version, version.filename,
                            name = catalogViewModel.appFor(pkg)?.name,
                        )
                    },
                )
            }
            composable(Routes.LIBRARY) { LibraryScreen(
                    libraryViewModel, moveViewModel,
                    onOpenMoveGuide = openMoveGuide,
                    onSubscribe = { openTab(Routes.SUBSCRIPTION) },
                ) }
            composable(Routes.MOVE_CHATS) {
                MoveChatsScreen(
                    viewModel = moveViewModel,
                    libraryViewModel = libraryViewModel,
                    onSubscribe = { openTab(Routes.SUBSCRIPTION) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SUBSCRIPTION) { SubscriptionScreen(viewModel(), authViewModel) }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    authViewModel,
                    updateViewModel,
                    moveViewModel = moveViewModel,
                    onOpenInstallSettings = openInstallSettings,
                    onReportBug = { navController.navigate(Routes.BUG_REPORT) },
                    onChangePassword = { navController.navigate(Routes.CHANGE_PASSWORD) },
                    onOpenMoveGuide = openMoveGuide,
                )
            }
            composable(Routes.BUG_REPORT) {
                // Scoped to this entry, so leaving the form discards it and the
                // next visit starts clean. The initializer is how the signed-in
                // account's email gets in: the default factory takes no arguments.
                val email = authState.user?.email
                val deviceContext = remember { StoreApp.instance.container.deviceContext() }
                BugReportScreen(
                    viewModel = viewModel { BugReportViewModel(deviceContext = deviceContext, defaultEmail = email) },
                    deviceContext = deviceContext,
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.CHANGE_PASSWORD) {
                // Scoped to this entry like the bug report, so leaving the form
                // drops whatever was typed into it.
                ChangePasswordScreen(viewModel = viewModel(), onDone = { navController.popBackStack() })
            }
        }
    }
}
