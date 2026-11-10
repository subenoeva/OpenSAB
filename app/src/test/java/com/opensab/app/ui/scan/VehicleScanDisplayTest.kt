package com.opensab.app.ui.scan

import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.ble.ScannedDevice
import com.opensab.sab.session.vehicle.KnownVehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleScanDisplayTest {

    private fun vehicle(name: String) = KnownVehicle(
        macAddress = "AA:BB:CC:11:22:33",
        displayName = name,
        vehicleInfo = VehicleInformation(
            vehicleType = 0x01,
            displayCapability = byteArrayOf(0x01, 0x02),
            switchType = SwitchType.TYPE_B,
            categoryId = 0x01,
            seriesCode = "0536",
            destinationId = DestinationId.EU
        )
    )

    @Test
    fun theScanButtonStopsAScanInProgress() =
        assertEquals(VehicleScanUiIntent.StopScan, VehicleScanUiState(isScanning = true).scanToggleIntent())

    @Test
    fun theScanButtonStartsAScanOtherwise() =
        assertEquals(VehicleScanUiIntent.StartScan, VehicleScanUiState(isScanning = false).scanToggleIntent())

    @Test
    fun aKnownVehicleConnectsAsACompatibleDevice() = assertEquals(
        ScannedDevice(name = "Mi CB", address = "AA:BB:CC:11:22:33", rssi = 0, isSabCompatible = true),
        vehicle("Mi CB").asScannedDevice(fallbackName = "Honda")
    )

    @Test
    fun aKnownVehicleWithoutANameTakesTheFallback() =
        assertEquals("Honda", vehicle("  ").asScannedDevice(fallbackName = "Honda").name)

    @Test
    fun onlyTheDeviceBeingPairedShowsProgress() {
        val state = VehicleScanUiState(pairingAddress = "AA:BB:CC:11:22:33")

        assertTrue(state.isPairing("AA:BB:CC:11:22:33"))
        assertFalse(state.isPairing("DD:EE:FF:44:55:66"))
    }

    @Test
    fun noCardConnectsWhilePairingRuns() {
        assertFalse(VehicleScanUiState(pairingAddress = "AA:BB:CC:11:22:33").canConnect)
        assertTrue(VehicleScanUiState(pairingAddress = null).canConnect)
    }

    @Test
    fun theSignalLineShowsAddressAndRssi() = assertEquals(
        "AA:BB:CC:11:22:33 • -61 dBm",
        ScannedDevice(name = "X", address = "AA:BB:CC:11:22:33", rssi = -61, isSabCompatible = true).signalLine()
    )
}
