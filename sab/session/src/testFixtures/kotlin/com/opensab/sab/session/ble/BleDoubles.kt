package com.opensab.sab.session.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **Owner: the test that constructs it.** It lives in testFixtures and not in `src/main` because a
 * double in production source is a double that can reach production wiring.
 */
class MockBleScanner : BleScanner {

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<ScannedDevice>> = _discoveredDevices.asStateFlow()

    fun emitDiscoveredDevice(device: ScannedDevice) {
        val current = _discoveredDevices.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.address == device.address }
        if (existingIndex != -1) {
            current[existingIndex] = device
        } else {
            current.add(device)
        }
        _discoveredDevices.value = current
    }

    /**
     * How many times a scan was asked for.
     *
     * The assertion that matters when the permission is missing is that the **scanner was not
     * called**, and `isScanning == false` is also true of a scan that was called and threw. Two
     * different facts, and a test has to look at its own.
     */
    var startScanCalls: Int = 0
        private set

    /** What the next [startScan] throws, and only the next one. */
    var failNextScanWith: Throwable? = null

    override fun startScan() {
        startScanCalls++
        failNextScanWith?.let {
            failNextScanWith = null
            throw it
        }
        _isScanning.value = true
    }

    override fun stopScan() {
        _isScanning.value = false
    }
}
