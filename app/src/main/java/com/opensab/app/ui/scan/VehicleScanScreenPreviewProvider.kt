package com.opensab.app.ui.scan

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.opensab.app.ui.preview.PreviewMockData

class VehicleScanScreenPreviewProvider : PreviewParameterProvider<VehicleScanUiState> {
    override val values: Sequence<VehicleScanUiState> = sequenceOf(
        // Scenario 1: Idle with known vehicles
        VehicleScanUiState(
            isScanning = false,
            scannedDevices = emptyList(),
            knownVehicles = listOf(PreviewMockData.sampleVehicle1, PreviewMockData.sampleVehicle2)
        ),
        // Scenario 2: Actively scanning with discovered devices
        VehicleScanUiState(
            isScanning = true,
            scannedDevices = PreviewMockData.sampleScannedDevices,
            knownVehicles = listOf(PreviewMockData.sampleVehicle1)
        ),
        // Scenario 3: In-flight Bluetooth bonding / pairing
        VehicleScanUiState(
            isScanning = false,
            scannedDevices = PreviewMockData.sampleScannedDevices,
            pairingAddress = "02:00:00:11:22:33",
            knownVehicles = emptyList()
        ),
        // Scenario 4: Error state with banner
        VehicleScanUiState(
            isScanning = false,
            error = ScanError.BondFailed(
                detail = "Pairing with 02:00:00:11:22:33 failed: UNBOND_REASON_AUTH_FAILED"
            ),
            scannedDevices = PreviewMockData.sampleScannedDevices,
            knownVehicles = listOf(PreviewMockData.sampleVehicle1)
        )
    )
}
