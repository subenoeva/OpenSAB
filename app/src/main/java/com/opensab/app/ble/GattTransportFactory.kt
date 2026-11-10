package com.opensab.app.ble

import com.opensab.sab.session.ble.GattTransport

/**
 * Resolves a MAC address into a live, connected [GattTransport].
 *
 * The UI only ever knows a motorcycle by its address, and building a transport is a platform
 * concern, so the factory keeps `android.bluetooth` out of the ViewModel and lets tests swap in
 * a [MockGattTransport] without touching a radio.
 */
interface GattTransportFactory {
    /** Creates a transport for [macAddress] and completes the GATT connection handshake. */
    suspend fun connect(macAddress: String): Result<GattTransport>
}
