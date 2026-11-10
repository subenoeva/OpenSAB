package com.opensab.sab.session.ble

import kotlinx.coroutines.flow.StateFlow

interface BleScanner {

    val isScanning: StateFlow<Boolean>
    val discoveredDevices: StateFlow<List<ScannedDevice>>

    fun startScan()
    fun stopScan()
}
