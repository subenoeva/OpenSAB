package com.opensab.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.opensab.logging.Log
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.session.ble.BleScanner
import com.opensab.sab.session.ble.ScannedDevice
import com.opensab.sab.session.ble.sanitizeDeviceName
import com.opensab.sab.session.ble.upsertByAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Production [BleScanner] backed by the Android [android.bluetooth.le.BluetoothLeScanner].
 *
 * Which of the SAB services the BTU actually puts in its advertisement is still unconfirmed
 * against real hardware, so the scanner filters on all of them (multiple [ScanFilter] entries
 * are OR'd by the framework). Setting [includeUnfilteredResults] disables filtering entirely and
 * reports every nearby peripheral, flagging the ones whose advertisement carries a SAB service —
 * useful for the first bring-up session against a real motorcycle.
 *
 * Callers must hold BLUETOOTH_SCAN (API 31+) or ACCESS_FINE_LOCATION (API 26-30) before calling
 * [startScan], and nothing here checks: there is no permission wall around the app. What stands in
 * its place is a state and an error: `VehicleScanUiState.blePermissionsGranted` keeps [startScan]
 * from being called at all, and the
 * `SecurityException` this throws when the permission goes away mid-scan reaches the rider as
 * `ScanError.PermissionsMissing` instead of a silent crash.
 */
@SuppressLint("MissingPermission")
class AndroidBleScanner(
    context: Context,
    private val includeUnfilteredResults: Boolean = false
) : BleScanner {

    private companion object {
        val SAB_SERVICE_UUIDS = listOf(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.SERVICE_SECURITY,
            SabGattUuids.SERVICE_VEHICLE_INFO,
            SabGattUuids.SERVICE_ASSIGNMENT,
            SabGattUuids.SERVICE_DFU
        )
    }

    private val bluetoothManager =
        context.applicationContext.getSystemService(BluetoothManager::class.java)

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<ScannedDevice>> = _discoveredDevices.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE scan failed with error code $errorCode")
            _isScanning.value = false
        }
    }

    override fun startScan() {
        if (_isScanning.value) return

        val scanner = bluetoothManager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            Log.w("Cannot start scan: Bluetooth adapter unavailable or disabled")
            return
        }

        _discoveredDevices.value = emptyList()

        val filters = if (includeUnfilteredResults) {
            emptyList()
        } else {
            SAB_SERVICE_UUIDS.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.i("Starting BLE scan (${filters.size} service filters)")
        scanner.startScan(filters, settings, scanCallback)
        _isScanning.value = true
    }

    override fun stopScan() {
        if (!_isScanning.value) return

        val scanner = bluetoothManager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner != null) {
            Log.i("Stopping BLE scan")
            scanner.stopScan(scanCallback)
        }
        _isScanning.value = false
    }

    private fun record(result: ScanResult) {
        val advertisedServices = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
        val rawName = result.scanRecord?.deviceName ?: result.device.name
        val device = ScannedDevice(
            name = sanitizeDeviceName(rawName),
            address = result.device.address,
            rssi = result.rssi,
            isSabCompatible = advertisedServices.any { it in SAB_SERVICE_UUIDS }
        )
        _discoveredDevices.update { current -> current.upsertByAddress(device) }
    }
}
