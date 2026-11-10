package com.opensab.app.ui.dashboard

import androidx.annotation.StringRes
import com.opensab.cluster.mirror.MirrorState
import com.opensab.domain.music.MusicState
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.vehicle.KnownVehicle

data class DashboardUiState(
    val sessionState: SabSessionState = SabSessionState.Disconnected,
    val activeVehicle: KnownVehicle? = null,
    val musicState: MusicState = MusicState(),
    val mirrorState: MirrorState = MirrorState.Idle,

    /**
     * Defaults to `true`, unlike [com.opensab.app.ui.scan.VehicleScanUiState.blePermissionsGranted]: the
     * card **appears** when the grant is missing, so a `false` default would flash it on a phone
     * that has it. `init` corrects it before there is a collector, so the default only governs
     * previews.
     */
    val mediaAccessGranted: Boolean = true
)

sealed interface DashboardUiIntent {
    data object Disconnect : DashboardUiIntent
    data object OpenSettings : DashboardUiIntent

    /** A tap on a mirrored zone, carrying the very press that zone stands for. */
    data class MirrorAction(val functionId: FunctionId) : DashboardUiIntent

    /** The rider asking the cluster to hand the screen over. Never implicit. */
    data object RequestHandover : DashboardUiIntent

    /** Sent on ON_RESUME. */
    data object RefreshPermissions : DashboardUiIntent
    data object OpenPermissions : DashboardUiIntent
}

sealed interface DashboardUiEvent {
    data object NavigateToScan : DashboardUiEvent
    data object NavigateToPermissions : DashboardUiEvent
    data object NavigateToSettings : DashboardUiEvent
    data class ShowToast(@StringRes val messageResId: Int) : DashboardUiEvent
}
