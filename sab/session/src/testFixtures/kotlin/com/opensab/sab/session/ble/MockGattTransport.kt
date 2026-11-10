package com.opensab.sab.session.ble

import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MockGattTransport(
    private val operationQueue: SerializedGattQueue = SerializedGattQueue(),
    var onCharacteristicWrite: (suspend (UUID, ByteArray) -> Unit)? = null
) : GattTransport {

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private var _negotiatedMtu: Int = 23
    override val negotiatedMtu: Int get() = _negotiatedMtu

    private val characteristicStore = ConcurrentHashMap<UUID, ByteArray>()
    private val notificationFlows = ConcurrentHashMap<UUID, MutableSharedFlow<ByteArray>>()

    fun connect() {
        _connectionState.value = BleConnectionState.Connected(_negotiatedMtu)
    }

    fun setCharacteristicValue(characteristicUuid: UUID, payload: ByteArray) {
        characteristicStore[characteristicUuid] = payload.clone()
    }

    suspend fun emitNotification(serviceUuid: UUID, characteristicUuid: UUID, payload: ByteArray) {
        val flow = notificationFlows.computeIfAbsent(characteristicUuid) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 64)
        }
        flow.emit(payload.clone())
    }

    override suspend fun writeCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        payload: ByteArray,
        withResponse: Boolean
    ): Result<ByteArray> = runCatchingCancellable {
        if (_connectionState.value !is BleConnectionState.Connected) {
            throw BleDisconnectedException("Cannot write: transport is not connected")
        }
        operationQueue.execute("Write to $characteristicUuid") {
            characteristicStore[characteristicUuid] = payload.clone()
            onCharacteristicWrite?.invoke(characteristicUuid, payload)
            payload.clone()
        }
    }

    override suspend fun readCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): Result<ByteArray> = runCatchingCancellable {
        if (_connectionState.value !is BleConnectionState.Connected) {
            throw BleDisconnectedException("Cannot read: transport is not connected")
        }
        characteristicStore[characteristicUuid]?.clone()
            ?: throw GattCharacteristicNotFoundException(characteristicUuid)
    }

    override fun observeNotifications(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        onSubscriptionArmed: (suspend () -> Unit)?
    ): Flow<ByteArray> {
        val flow = notificationFlows.computeIfAbsent(characteristicUuid) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 64)
        }
        return flow.asSharedFlow().onStart {
            onSubscriptionArmed?.invoke()
        }
    }

    override suspend fun requestMtu(mtu: Int): Result<Int> = runCatchingCancellable {
        if (_connectionState.value !is BleConnectionState.Connected && _connectionState.value !is BleConnectionState.Connecting) {
            throw BleDisconnectedException("Cannot request MTU: transport is not connected")
        }
        _negotiatedMtu = mtu.coerceIn(23, 517)
        _connectionState.value = BleConnectionState.Connected(_negotiatedMtu)
        _negotiatedMtu
    }

    override suspend fun disconnect() {
        _connectionState.value = BleConnectionState.Disconnected
        _negotiatedMtu = 23
        characteristicStore.clear()
    }
}
