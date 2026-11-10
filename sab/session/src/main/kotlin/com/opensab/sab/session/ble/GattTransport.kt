package com.opensab.sab.session.ble

import com.opensab.sab.protocol.ble.SabGattUuids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

sealed interface BleConnectionState {
    data object Disconnected : BleConnectionState
    data object Connecting : BleConnectionState
    data object RequestingMtu : BleConnectionState
    data object DiscoveringServices : BleConnectionState
    data class Connected(val negotiatedMtu: Int) : BleConnectionState
}

interface GattTransport {
    val connectionState: StateFlow<BleConnectionState>
    val negotiatedMtu: Int

    /** On success, the bytes that were written. */
    suspend fun writeCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        payload: ByteArray,
        withResponse: Boolean = true
    ): Result<ByteArray>

    suspend fun readCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): Result<ByteArray>

    fun observeNotifications(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        onSubscriptionArmed: (suspend () -> Unit)? = null
    ): Flow<ByteArray>

    suspend fun requestMtu(mtu: Int = SabGattUuids.TARGET_MTU): Result<Int>
    suspend fun disconnect()
}
