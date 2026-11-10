package com.opensab.app.ui.scan

import com.opensab.sab.session.ble.ScannedDevice
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.KnownVehicle

/** Which step of a connection attempt failed; the composable words it from `strings.xml`. */
sealed interface ScanError {

    /**
     * [detail] is the decoded `UNBOND_REASON_*`, null when the stack reported none. A stale link key
     * survives on the phone and no retry clears it: the rider has to forget the motorcycle in
     * Bluetooth settings, because `removeBond` is not in the public SDK.
     */
    data class BondFailed(val detail: String?) : ScanError

    /**
     * Separate from [BondFailed] because the advice differs: a bond that times out without a reason
     * is a pairing prompt nobody accepted, not a stale link key.
     */
    data object BondTimedOut : ScanError

    data object TransportUnreachable : ScanError

    /** The handshake pipeline failed with the link already open. */
    data class PipelineFailed(val detail: String?) : ScanError

    /**
     * The permission went away **between** the [VehicleScanUiState.blePermissionsGranted] check and
     * the scan.
     */
    data object PermissionsMissing : ScanError
}

data class VehicleScanUiState(
    val isScanning: Boolean = false,
    val scannedDevices: List<ScannedDevice> = emptyList(),
    val knownVehicles: List<KnownVehicle> = emptyList(),
    val pairingAddress: String? = null,
    val error: ScanError? = null,

    /** Set only for a device with no stored identity: nothing on the link reports the cluster. */
    val askClusterFor: ScannedDevice? = null,

    /**
     * `false` stops `StartScan` from reaching the scanner. Defaults to `false`: offering to scan and
     * then failing is worse than waiting a frame to find out.
     */
    val blePermissionsGranted: Boolean = false
)

sealed interface VehicleScanUiIntent {
    data object StartScan : VehicleScanUiIntent
    data object StopScan : VehicleScanUiIntent
    data class ConnectToDevice(val device: ScannedDevice) : VehicleScanUiIntent

    /** Answers the cluster question [VehicleScanUiState.askClusterFor] raised, then connects. */
    data class AnswerClusterDisplay(val clusterDisplay: ClusterDisplay) : VehicleScanUiIntent

    data object DismissClusterQuestion : VehicleScanUiIntent

    /** Aborts the pairing and pipeline started by [ConnectToDevice]. */
    data object CancelConnect : VehicleScanUiIntent
    data object DismissError : VehicleScanUiIntent

    /** Sent on ON_RESUME. */
    data object RefreshPermissions : VehicleScanUiIntent
    data object OpenPermissions : VehicleScanUiIntent
    data object OpenSettings : VehicleScanUiIntent
}

sealed interface VehicleScanUiEvent {
    /** Surfaces [error] as a toast, for a rider who is not looking at the banner. */
    data class ShowError(val error: ScanError) : VehicleScanUiEvent
    data object NavigateToDashboard : VehicleScanUiEvent
    data object NavigateToPermissions : VehicleScanUiEvent
    data object NavigateToSettings : VehicleScanUiEvent
}
