package com.opensab.sab.session.ble.cts

/**
 * Controller interface for Android BluetoothGattServer hosting Current Time Service (CTS 0x1805).
 */
interface CtsServer {
    fun start(): Boolean
    fun stop()
    fun isRunning(): Boolean
    fun notifyTimeUpdated()
}
