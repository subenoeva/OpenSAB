package com.opensab.app.ui.dashboard

import androidx.lifecycle.viewModelScope
import com.opensab.app.service.SessionLauncher
import com.opensab.app.ui.base.BaseViewModel
import com.opensab.app.ui.permissions.PermissionEntry
import com.opensab.app.ui.permissions.PermissionId
import com.opensab.app.ui.permissions.PermissionStatus
import com.opensab.app.ui.permissions.PermissionStatusReader
import com.opensab.cluster.ClusterHandoverCoordinator
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.domain.music.MusicManager
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.SabSessions
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.input.InputDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Owns the live dashboard: session telemetry, projected music, navigation and call widgets. */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val musicManager: MusicManager,
    private val handoverCoordinator: ClusterHandoverCoordinator,
    private val sabSessions: SabSessions,
    /** The single door into a session: this screen never disconnects one itself. */
    private val sessionLauncher: SessionLauncher,
    projectionMirror: ProjectionMirror,
    private val inputDispatcher: InputDispatcher,
    private val permissionEntries: List<PermissionEntry>,
    private val permissionStatusReader: PermissionStatusReader
) : BaseViewModel<DashboardUiState, DashboardUiIntent, DashboardUiEvent>(DashboardUiState()) {

    init {
        refreshPermissions()

        // Deliberately NOT whileSubscribed(): this observer navigates back to the scan screen when
        // the link dies on its own. Stopping it while nothing is subscribed would strand the rider
        // on a dashboard that is no longer talking to anything.
        viewModelScope.launch {
            var wasConnected = false
            sabSessions.state.collect { session ->
                val connected = session is SabSessionState.Connected
                updateState {
                    copy(
                        sessionState = session,
                        // Keep the last known motorcycle on screen while the link drops: the
                        // dashboard would otherwise blank out its identity mid-ride.
                        activeVehicle = (session as? SabSessionState.Connected)?.vehicle
                            ?: activeVehicle
                    )
                }
                // Covers the rider disconnecting and the link dying on its own alike, so the
                // Disconnect intent does not have to emit the event itself.
                if (wasConnected && !connected) {
                    sendEvent(DashboardUiEvent.NavigateToScan)
                }
                wasConnected = connected
            }
        }

        // Music only paints the widget, so it idles while the screen is not observed.
        viewModelScope.launch {
            musicManager.currentState.whileSubscribed().collect { music ->
                updateState { copy(musicState = music) }
            }
        }

        // Unlike the session observer, this one only paints and navigates nowhere, so there is no
        // point in it running with the screen in the background.
        viewModelScope.launch {
            projectionMirror.state.whileSubscribed().collect { mirror ->
                updateState { copy(mirrorState = mirror) }
            }
        }
    }

    override suspend fun handleIntent(intent: DashboardUiIntent) {
        when (intent) {
            is DashboardUiIntent.Disconnect -> {
                Timber.i("Disconnect intent received on DashboardViewModel")
                sessionLauncher.stop(SessionOrigin.DASHBOARD)
            }
            is DashboardUiIntent.OpenSettings -> {
                sendEvent(DashboardUiEvent.NavigateToSettings)
            }
            is DashboardUiIntent.MirrorAction -> {
                // The zones are already disabled outside SAB_MODE, so a refusal here means the two
                // states crossed. Logged and dropped: taking the dashboard down over a press that
                // simply arrived too late would be the worse failure.
                inputDispatcher.injectMirrorPress(intent.functionId).onFailure { error ->
                    Timber.w(error, "Mirror press %s was refused", intent.functionId)
                }
            }
            is DashboardUiIntent.RequestHandover -> {
                Timber.i("Rider asked the cluster to hand the screen over")
                handoverCoordinator.requestClusterHandover().onFailure { error ->
                    Timber.w(error, "Could not ask the cluster for the screen")
                }
            }
            is DashboardUiIntent.RefreshPermissions -> {
                refreshPermissions()
            }
            is DashboardUiIntent.OpenPermissions -> {
                sendEvent(DashboardUiEvent.NavigateToPermissions)
            }
        }
    }

    private fun refreshPermissions() {
        val granted = permissionEntries
            .filter { it.id == PermissionId.NOTIFICATION_ACCESS }
            .all { permissionStatusReader.statusOf(it) == PermissionStatus.GRANTED }

        updateState { copy(mediaAccessGranted = granted) }
    }
}
