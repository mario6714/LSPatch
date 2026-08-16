@file:OptIn(
    ExperimentalMaterial3AdaptiveNavigationSuiteApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package org.lsposed.lspatch.ui.activity

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.google.accompanist.navigation.animation.rememberAnimatedNavController
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.spec.Direction
import com.ramcosta.composedestinations.spec.DirectionDestinationSpec
import org.lsposed.lspatch.ui.navigation.TopLevelRoute
import org.lsposed.lspatch.ui.navigation.TOP_LEVEL_DESTINATIONS
import org.matrix.vector.ui.navigation.FloatingPanelNav
import org.matrix.vector.ui.navigation.NavPanels
import org.matrix.vector.ui.navigation.PanelBar
import org.matrix.vector.ui.navigation.PanelEditDone
import org.matrix.vector.ui.navigation.decodeNavPanels
import org.matrix.vector.ui.navigation.encodeNavPanels
import org.lsposed.lspatch.ui.appearance.LSPFloatingNavSettings
import org.lsposed.lspatch.ui.page.NavGraphs
import org.lsposed.lspatch.ui.page.appCurrentDestinationAsState
import org.lsposed.lspatch.ui.page.destinations.Destination
import org.lsposed.lspatch.ui.page.destinations.HomeScreenDestination
import org.lsposed.lspatch.ui.page.destinations.LogsScreenDestination
import org.lsposed.lspatch.ui.page.destinations.ManageScreenDestination
import org.lsposed.lspatch.ui.page.destinations.RepoScreenDestination
import org.lsposed.lspatch.ui.page.startAppDestination
import org.lsposed.lspatch.ui.appearance.LSPSettings
import org.matrix.vector.ui.locale.LocalizedContent
import org.matrix.vector.ui.locale.LocalizedOverlay
import org.lsposed.lspatch.ui.theme.LSPTheme
import org.lsposed.lspatch.ui.util.LocalSnackbarHost
import org.matrix.vector.ui.LocalDialogLocalizer

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalAnimationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val navController = rememberAnimatedNavController()
            val themeMode by LSPSettings.themeMode.collectAsState()
            val dynamicColor by LSPSettings.dynamicColor.collectAsState()
            val seed by LSPSettings.seedColor.collectAsState()
            val amoled by LSPSettings.amoledBlack.collectAsState()
            LSPTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
                seed = seed,
                amoled = amoled,
            ) {
                // The chosen language re-resolves every string below, and the localizer the shared
                // library reads is pointed at LSPatch's overlay so its sheets follow suit.
                LocalizedContent(LSPSettings) {
                    CompositionLocalProvider(
                        LocalDialogLocalizer provides { content -> LocalizedOverlay(LSPSettings, content) }
                    ) {
                val snackbarHostState = remember { SnackbarHostState() }
                CompositionLocalProvider(LocalSnackbarHost provides snackbarHostState) {
                    val context = LocalContext.current
                    val prefs = remember { context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE) }
                    var panels by remember {
                        mutableStateOf(
                            decodeNavPanels(prefs.getString(KEY_NAV_PANELS, "") ?: "", TOP_LEVEL_DESTINATIONS)
                        )
                    }
                    fun persist(next: NavPanels) {
                        panels = next
                        prefs.edit().putString(KEY_NAV_PANELS, encodeNavPanels(next)).apply()
                    }
                    var editing by remember { mutableStateOf(false) }

                    val currentDestination: Destination = navController.appCurrentDestinationAsState().value
                        ?: NavGraphs.root.startAppDestination
                    val currentTop = currentDestination.toTopLevelRoute()
                    val atRoot = currentTop != null

                    val suiteState = rememberNavigationSuiteScaffoldState()
                    LaunchedEffect(atRoot) { if (atRoot) suiteState.show() else suiteState.hide() }
                    // Leaving a root screen also cancels an in-progress panel edit.
                    LaunchedEffect(atRoot) { if (!atRoot) editing = false }

                    val floating by LSPSettings.floatingNav.collectAsState()
                    // Floating overrules the adaptive bar/rail with None — the type that actually
                    // removes the container rather than hiding it — except while editing panels,
                    // when there has to be a bar to rearrange.
                    val suiteType =
                        if (floating && !editing) NavigationSuiteType.None
                        else NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())

                    NavigationSuiteScaffold(
                        state = suiteState,
                        navigationSuiteType = suiteType,
                        navigationItems = {
                            // Under None the NavigationSuite drops this slot with its container, so
                            // skipping it says so rather than leaving a composable that never runs.
                            if (suiteType != NavigationSuiteType.None) {
                                PanelBar(
                                    panels = panels,
                                    currentKey = currentTop?.key ?: panels.start.key,
                                    editing = editing,
                                    suiteType = suiteType,
                                    onSelect = { destination ->
                                        editing = false
                                        navController.navigate(destinationForKey(destination.key).route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    onEdit = { editing = true },
                                    onToggleHidden = { key, hidden -> persist(panels.withHidden(key, hidden)) },
                                    onMove = { from, to -> persist(panels.withMoved(from, to)) },
                                )
                            }
                        },
                        primaryActionContent = {
                            if (editing) PanelEditDone(onDone = { editing = false })
                        },
                    ) {
                        // Single inset owner: each screen's own Scaffold consumes the status-bar inset
                        // (edge-to-edge), so nothing here re-applies it. The snackbar is overlaid.
                        Box(Modifier.fillMaxSize()) {
                            DestinationsNavHost(
                                navGraph = NavGraphs.root,
                                navController = navController
                            )
                            // Last child so it draws over the destination, and only at a root panel
                            // (a detail screen has its own back affordance) and not mid-edit.
                            if (floating && !editing && atRoot) {
                                FloatingPanelNav(
                                    panels = panels,
                                    currentKey = currentTop?.key ?: panels.start.key,
                                    onSelect = { destination ->
                                        navController.navigate(destinationForKey(destination.key).route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    settings = LSPFloatingNavSettings,
                                )
                            }
                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                            )
                        }
                    }
                }
                    }
                }
            }
        }
    }
}

private const val KEY_NAV_PANELS = "nav_panels"

/** The compose-destinations screen a top-level panel's key points at. */
private fun destinationForKey(key: String): Direction = when (key) {
    TopLevelRoute.Store.key -> RepoScreenDestination
    // Manage now carries an initial-tab arg, so it must be invoked to become a Direction; its
    // default opens the Applications tab, which is what the bar wants.
    TopLevelRoute.Manage.key -> ManageScreenDestination()
    TopLevelRoute.Logs.key -> LogsScreenDestination
    else -> HomeScreenDestination
}

/** null when the current screen is not one of the four top-level panels (e.g. New Patch). */
private fun Destination.toTopLevelRoute(): TopLevelRoute? = when (this) {
    HomeScreenDestination -> TopLevelRoute.Home
    RepoScreenDestination -> TopLevelRoute.Store
    ManageScreenDestination -> TopLevelRoute.Manage
    LogsScreenDestination -> TopLevelRoute.Logs
    else -> null
}
