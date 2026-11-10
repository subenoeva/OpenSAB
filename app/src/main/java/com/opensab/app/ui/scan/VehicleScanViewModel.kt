package com.opensab.app.ui.scan

import androidx.lifecycle.viewModelScope
import com.opensab.app.service.SessionLauncher
import com.opensab.app.ui.base.BaseViewModel
import com.opensab.app.ui.permissions.PermissionEntry
import com.opensab.app.ui.permissions.PermissionId
import com.opensab.app.ui.permissions.PermissionStatus
import com.opensab.app.ui.permissions.PermissionStatusReader
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.SabSessions
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.TransportUnreachableException
import com.opensab.sab.session.ble.BleScanner
import com.opensab.sab.session.ble.BondFailedException
import com.opensab.sab.session.ble.BondTimedOutException
import com.opensab.sab.session.ble.ScannedDevice
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.VehicleProfile
import com.opensab.sab.session.vehicle.VehicleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Owns the discovery screen: BLE scanning and asking the service to pair and connect. */
@HiltViewModel
class VehicleScanViewModel @Inject constructor(
    private val bleScanner: BleScanner,
    private val vehicleRepository: VehicleRepository,
    private val sabSessions: SabSessions,
    /** The single door into a session: this screen never opens one itself. */
    private val sessionLauncher: SessionLauncher,
    private val permissionEntries: List<PermissionEntry>,
    private val permissionStatusReader: PermissionStatusReader
) : BaseViewModel<VehicleScanUiState, VehicleScanUiIntent, VehicleScanUiEvent>(VehicleScanUiState()) {

    /**
     * `statusOf` does not suspend, so this runs from `init` before there is a collector and the
     * first state the screen sees is already true.
     */
    private fun refreshPermissions() {
        val granted = permissionEntries
            .filter { it.id == PermissionId.BLUETOOTH }
            .all { permissionStatusReader.statusOf(it) == PermissionStatus.GRANTED }

        updateState { copy(blePermissionsGranted = granted) }
    }

    /**
     * Two guards, and neither is redundant. Without the permission the banner explains it and the
     * scanner is never called. The `catch` covers the permission revoked **between** that check and
     * this call, which arrives as a `SecurityException` out of `AndroidBleScanner`.
     *
     * `catch (SecurityException)` and not `runCatching`: the latter swallows `CancellationException`.
     */
    private fun startScan() {
        if (!currentState.blePermissionsGranted) {
            Timber.i("Scan not started: the Bluetooth permission is missing")
            return
        }

        updateState { copy(error = null) }
        try {
            bleScanner.startScan()
        } catch (e: SecurityException) {
            Timber.w(e, "The Bluetooth permission went away mid-scan")
            updateState { copy(error = ScanError.PermissionsMissing) }
            sendEvent(VehicleScanUiEvent.ShowError(ScanError.PermissionsMissing))
        }
    }

    init {
        refreshPermissions()

        // These three only paint the screen, so they idle while nothing is subscribed to uiState.
        viewModelScope.launch {
            bleScanner.isScanning.whileSubscribed().collect { scanning ->
                updateState { copy(isScanning = scanning) }
            }
        }

        viewModelScope.launch {
            bleScanner.discoveredDevices.whileSubscribed().collect { devices ->
                updateState { copy(scannedDevices = devices) }
            }
        }

        viewModelScope.launch {
            vehicleRepository.vehiclesFlow.whileSubscribed().collect { vehicles ->
                updateState { copy(knownVehicles = vehicles) }
            }
        }

        // The single source of NavigateToDashboard, whoever opened the session: the rider tapping
        // a row here, or the service auto-connecting in the background. Emitting it from the
        // connect path as well raced (the observer can resume after the attempt has finished) and
        // the screen navigated twice.
        //
        // Deliberately NOT whileSubscribed(): this is what gets the app off the scan screen when
        // something else connects.
        //
        // It also settles this screen's own pending request (`pairingAddress != null`): a start
        // through SessionLauncher is fire-and-forget, so this is the only place that learns how it
        // went. Saving the paired vehicle is owned by `KnownVehicleParticipant`, not this screen.
        viewModelScope.launch {
            var wasConnected = false
            sabSessions.state.collect { state ->
                val connected = state is SabSessionState.Connected
                if (connected && !wasConnected) {
                    sendEvent(VehicleScanUiEvent.NavigateToDashboard)
                }
                wasConnected = connected

                if (currentState.pairingAddress != null) {
                    when (state) {
                        is SabSessionState.Connected -> updateState { copy(pairingAddress = null) }
                        is SabSessionState.Error -> failWith(scanErrorFor(state.cause))
                        else -> Unit
                    }
                }
            }
        }
    }

    override suspend fun handleIntent(intent: VehicleScanUiIntent) {
        when (intent) {
            is VehicleScanUiIntent.StartScan -> {
                startScan()
            }
            is VehicleScanUiIntent.StopScan -> {
                bleScanner.stopScan()
            }
            is VehicleScanUiIntent.ConnectToDevice -> {
                connectOrAskCluster(intent.device)
            }
            is VehicleScanUiIntent.AnswerClusterDisplay -> {
                answerClusterDisplay(intent.clusterDisplay)
            }
            is VehicleScanUiIntent.DismissClusterQuestion -> {
                updateState { copy(askClusterFor = null) }
            }
            is VehicleScanUiIntent.CancelConnect -> {
                cancelConnect()
            }
            is VehicleScanUiIntent.RefreshPermissions -> {
                refreshPermissions()
            }
            is VehicleScanUiIntent.OpenPermissions -> {
                sendEvent(VehicleScanUiEvent.NavigateToPermissions)
            }
            is VehicleScanUiIntent.DismissError -> {
                updateState { copy(error = null) }
            }
            is VehicleScanUiIntent.OpenSettings -> {
                sendEvent(VehicleScanUiEvent.NavigateToSettings)
            }
        }
    }

    /**
     * Nothing over the link reports the cluster type, so a device with no stored identity asks the
     * rider first.
     *
     * `currentState.knownVehicles` alone is not enough: it mirrors the DataStore-backed
     * [VehicleRepository.vehiclesFlow], which can still be catching up. Without the
     * [VehicleRepository.getVehicle] fallback a motorcycle on file would be re-asked, and the answer
     * would overwrite its stored profile.
     */
    private suspend fun connectOrAskCluster(device: ScannedDevice) {
        val known = currentState.knownVehicles.any { it.macAddress == device.address } ||
            vehicleRepository.getVehicle(device.address) != null
        if (known) {
            connectToDevice(device)
        } else {
            updateState { copy(askClusterFor = device) }
        }
    }

    private suspend fun answerClusterDisplay(clusterDisplay: ClusterDisplay) {
        val device = currentState.askClusterFor ?: return
        vehicleRepository.saveProfile(device.address, VehicleProfile(clusterDisplay = clusterDisplay))
        updateState { copy(askClusterFor = null) }
        connectToDevice(device)
    }

    /**
     * `SessionLauncher.start` never suspends, so this never blocks the intent collector behind a
     * bond that can take seconds.
     */
    private fun connectToDevice(device: ScannedDevice) {
        bleScanner.stopScan()
        updateState { copy(pairingAddress = device.address, error = null) }
        sessionLauncher.start(device.address, SessionOrigin.SCANNER)
    }

    private fun cancelConnect() {
        sessionLauncher.stop(SessionOrigin.SCANNER)
        updateState { copy(pairingAddress = null) }
    }

    private fun scanErrorFor(cause: Throwable): ScanError = when (cause) {
        is BondTimedOutException -> ScanError.BondTimedOut
        is BondFailedException -> ScanError.BondFailed(detail = cause.message)
        is TransportUnreachableException -> ScanError.TransportUnreachable
        else -> ScanError.PipelineFailed(detail = cause.message)
    }

    /** Leaves [error] on the banner and pushes it once as a toast; the screen words both. */
    private fun failWith(error: ScanError) {
        updateState { copy(pairingAddress = null, error = error) }
        sendEvent(VehicleScanUiEvent.ShowError(error))
    }
}
