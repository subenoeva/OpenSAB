package com.opensab.sab.session.crypto

import com.opensab.sab.session.ble.GattTransport

interface SecuritySessionManager {

    suspend fun establishSession(
        macAddress: String,
        transport: GattTransport
    ): Result<SecuritySession>
}
