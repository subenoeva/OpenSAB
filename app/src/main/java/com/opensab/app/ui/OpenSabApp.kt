package com.opensab.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.opensab.app.R
import com.opensab.app.ui.common.ObserveAsEvents
import com.opensab.app.ui.dashboard.DashboardLiveScreen
import com.opensab.app.ui.dashboard.DashboardUiEvent
import com.opensab.app.ui.dashboard.DashboardViewModel
import com.opensab.app.ui.navigation.AppRoute
import com.opensab.app.ui.permissions.PermissionsRoute
import com.opensab.app.ui.scan.VehicleScanScreen
import com.opensab.app.ui.scan.VehicleScanUiEvent
import com.opensab.app.ui.scan.VehicleScanViewModel
import com.opensab.app.ui.scan.resolve
import com.opensab.app.ui.sessions.SessionsScreen
import com.opensab.app.ui.sessions.SessionsUiEvent
import com.opensab.app.ui.sessions.SessionsViewModel
import com.opensab.app.ui.sessions.ShareZip
import com.opensab.app.ui.settings.SettingsScreen
import com.opensab.app.ui.settings.SettingsUiEvent
import com.opensab.app.ui.settings.SettingsViewModel
import com.opensab.app.ui.settings.rootmenu.RootMenuSettingsScreen
import com.opensab.app.ui.settings.rootmenu.RootMenuSettingsUiEvent
import com.opensab.app.ui.settings.rootmenu.RootMenuSettingsViewModel

/**
 * Navigation 3 host. Every route resolves its own Hilt ViewModel and reacts to its own one-off
 * events, so every move between routes has exactly one origin: an event, never a callback the
 * screen was handed.
 */
@Composable
fun OpenSabApp(modifier: Modifier = Modifier) {
    val startViewModel: AppStartViewModel = hiltViewModel()
    val startRoute: AppRoute? by startViewModel.startRoute.collectAsStateWithLifecycle()

    when (val route = startRoute) {
        // "Not known yet". One frame of the theme's background rather than a jump: composing
        // NavDisplay with Scan and correcting it once DataStore answers would flash the scan
        // screen on every first run.
        null -> Unit
        else -> OpenSabNavigation(route = route, modifier = modifier)
    }
}

/**
 * The host proper, composed only once the first route is known. `rememberNavBackStack` is handed a
 * route that has already been decided and is never re-evaluated: the flag is read once per process
 * and not observed, so a mid-session change of it does not move the rider.
 */
@Composable
private fun OpenSabNavigation(route: AppRoute, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack(route)

    // System back is NavDisplay's own NavigationBackHandler: it pops a single entry and stays
    // disabled while the stack has nothing to go back to. A BackHandler declared here would be
    // registered further out and never see the event anyway.
    NavDisplay(
        backStack = backStack,
        modifier = modifier.fillMaxSize(),
        // Without the ViewModelStore decorator every entry would resolve its ViewModel against the
        // Activity, so a popped screen would keep observing the orchestrator and replay its
        // buffered navigation events the next time it was shown.
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = entryProvider {
            entry<AppRoute.Scan> {
                val viewModel: VehicleScanViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                ObserveAsEvents(viewModel.uiEvents) { event ->
                    when (event) {
                        is VehicleScanUiEvent.NavigateToDashboard ->
                            backStack.replaceWith(AppRoute.Dashboard)
                        is VehicleScanUiEvent.NavigateToSettings ->
                            backStack.push(AppRoute.Settings)
                        is VehicleScanUiEvent.NavigateToPermissions ->
                            backStack.push(AppRoute.Permissions())
                        is VehicleScanUiEvent.ShowError ->
                            Toast.makeText(context, event.error.resolve(context), Toast.LENGTH_SHORT)
                                .show()
                    }
                }

                VehicleScanScreen(
                    state = state,
                    onIntent = viewModel::sendIntent,
                    modifier = Modifier.fillMaxSize()
                )
            }
            entry<AppRoute.Dashboard> {
                val viewModel: DashboardViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                ObserveAsEvents(viewModel.uiEvents) { event ->
                    when (event) {
                        is DashboardUiEvent.NavigateToScan ->
                            backStack.replaceWith(AppRoute.Scan)
                        is DashboardUiEvent.NavigateToSettings ->
                            backStack.push(AppRoute.Settings)
                        is DashboardUiEvent.NavigateToPermissions ->
                            backStack.push(AppRoute.Permissions())
                        is DashboardUiEvent.ShowToast ->
                            Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                    }
                }

                DashboardLiveScreen(
                    state = state,
                    onIntent = viewModel::sendIntent,
                    modifier = Modifier.fillMaxSize()
                )
            }
            entry<AppRoute.RootMenuSettings> {
                val viewModel: RootMenuSettingsViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                ObserveAsEvents(viewModel.uiEvents) { event ->
                    when (event) {
                        RootMenuSettingsUiEvent.NavigateBack -> backStack.pop()
                    }
                }

                RootMenuSettingsScreen(
                    state = state,
                    onIntent = viewModel::sendIntent,
                    modifier = Modifier.fillMaxSize()
                )
            }
            entry<AppRoute.Permissions> { key ->
                PermissionsRoute(
                    isIntro = key.isIntro,
                    // The intro is not a step a rider can walk back through.
                    onNavigateToScan = { backStack.replaceWith(AppRoute.Scan) },
                    // Reached from Settings, from the scan banner or from the dashboard card it was
                    // an ordinary push, and the arrow goes back.
                    onNavigateBack = { backStack.pop() },
                    modifier = Modifier.fillMaxSize()
                )
            }
            entry<AppRoute.Settings> {
                val viewModel: SettingsViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                ObserveAsEvents(viewModel.uiEvents) { event ->
                    when (event) {
                        is SettingsUiEvent.NavigateBack -> backStack.pop()
                        is SettingsUiEvent.NavigateToPermissions ->
                            backStack.push(AppRoute.Permissions())
                        is SettingsUiEvent.NavigateToRootMenu ->
                            backStack.push(AppRoute.RootMenuSettings)
                        is SettingsUiEvent.ShowToast ->
                            Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                        is SettingsUiEvent.NavigateToSessions ->
                            backStack.push(AppRoute.Sessions)
                    }
                }

                SettingsScreen(
                    state = state,
                    onIntent = viewModel::sendIntent,
                    modifier = Modifier.fillMaxSize()
                )
            }
            entry<AppRoute.Sessions> {
                val viewModel: SessionsViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                ObserveAsEvents(viewModel.uiEvents) { event ->
                    when (event) {
                        SessionsUiEvent.NavigateBack -> backStack.pop()
                        SessionsUiEvent.ExportFailed ->
                            Toast.makeText(context, R.string.sessions_export_failed, Toast.LENGTH_SHORT).show()
                        is SessionsUiEvent.Share -> ShareZip.launch(context, event.zip)
                    }
                }

                SessionsScreen(
                    state = state,
                    onIntent = viewModel::sendIntent,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    )
}

/** Pushes [route] unless it is already on top, so a repeated event cannot stack duplicates. */
private fun NavBackStack<NavKey>.push(route: AppRoute) {
    if (lastOrNull() != route) add(route)
}

/**
 * Makes [route] the only entry in the stack. Connecting and disconnecting are not steps a rider can
 * walk back through, so the previous screen must not survive in the back stack.
 */
private fun NavBackStack<NavKey>.replaceWith(route: AppRoute) {
    if (size == 1 && lastOrNull() == route) return
    clear()
    add(route)
}

private fun NavBackStack<NavKey>.pop() {
    if (size > 1) removeAt(lastIndex)
}
