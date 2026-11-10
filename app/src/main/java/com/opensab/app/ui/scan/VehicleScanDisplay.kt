package com.opensab.app.ui.scan

import com.opensab.app.ui.common.toCardUi
import com.opensab.sab.session.ble.ScannedDevice
import com.opensab.sab.session.vehicle.KnownVehicle

fun VehicleScanUiState.scanToggleIntent(): VehicleScanUiIntent =
    if (isScanning) VehicleScanUiIntent.StopScan else VehicleScanUiIntent.StartScan

/** A stored vehicle connects through the same intent as a scanned one; it was compatible when paired. */
fun KnownVehicle.asScannedDevice(fallbackName: String): ScannedDevice = ScannedDevice(
    name = toCardUi().title.ifBlank { fallbackName },
    address = macAddress,
    rssi = 0,
    isSabCompatible = true
)

fun VehicleScanUiState.isPairing(address: String): Boolean = address == pairingAddress

/** One pairing at a time: every card stays disabled while one runs. */
val VehicleScanUiState.canConnect: Boolean get() = pairingAddress == null

fun ScannedDevice.signalLine(): String = "$address • $rssi dBm"
