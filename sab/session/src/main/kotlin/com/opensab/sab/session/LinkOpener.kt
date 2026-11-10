package com.opensab.sab.session

import com.opensab.sab.session.ble.GattTransport

/**
 * Opens the BLE link to a motorcycle, without the controller ever seeing `GattTransportFactory`.
 * `:app` adapts it with `GattTransportFactory::connect`.
 */
fun interface LinkOpener {
    suspend fun open(macAddress: String): Result<GattTransport>
}
