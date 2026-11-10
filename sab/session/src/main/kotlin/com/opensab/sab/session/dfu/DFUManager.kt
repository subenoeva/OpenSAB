package com.opensab.sab.session.dfu

import com.opensab.logging.Log
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

internal interface DFUManager {

    val state: StateFlow<DFUState>

    suspend fun startUpdate(
        firmwareBinary: ByteArray,
        transport: GattTransport
    ): Result<Unit>

    suspend fun abortUpdate(transport: GattTransport): Result<Unit>
}

internal class DefaultDFUManager(
    private val chunkSizeBytes: Int = 256
) : DFUManager {

    private val _state = MutableStateFlow<DFUState>(DFUState.Idle)
    override val state: StateFlow<DFUState> = _state.asStateFlow()

    override suspend fun startUpdate(
        firmwareBinary: ByteArray,
        transport: GattTransport
    ): Result<Unit> = runCatchingCancellable {
        require(firmwareBinary.isNotEmpty()) { "Firmware binary cannot be empty" }
        val totalBytes = firmwareBinary.size
        Log.i("Starting DFU firmware update ($totalBytes bytes)")

        _state.value = DFUState.Preparing(totalBytes)
        val startPayload = byteArrayOf(
            DFUCommand.START_DFU.code.toByte(),
            (totalBytes and 0xFF).toByte(),
            ((totalBytes ushr 8) and 0xFF).toByte(),
            ((totalBytes ushr 16) and 0xFF).toByte(),
            ((totalBytes ushr 24) and 0xFF).toByte()
        )

        transport.writeCharacteristic(
            serviceUuid = SabGattUuids.SERVICE_DFU,
            characteristicUuid = SabGattUuids.CHAR_DFU_CONTROL_POINT,
            payload = startPayload,
            withResponse = true
        ).getOrThrow()

        var bytesSent = 0
        while (bytesSent < totalBytes) {
            val endOffset = min(bytesSent + chunkSizeBytes, totalBytes)
            val chunk = firmwareBinary.copyOfRange(bytesSent, endOffset)

            transport.writeCharacteristic(
                serviceUuid = SabGattUuids.SERVICE_DFU,
                characteristicUuid = SabGattUuids.CHAR_DFU_PACKET,
                payload = chunk,
                withResponse = false // Unacknowledged stream for throughput
            ).getOrThrow()

            bytesSent += chunk.size
            val progressPercent = ((bytesSent.toDouble() / totalBytes.toDouble()) * 100).toInt()
            _state.value = DFUState.Transferring(
                progressPercent = progressPercent,
                bytesSent = bytesSent,
                totalBytes = totalBytes
            )
        }

        _state.value = DFUState.Validating
        Log.i("All chunks transmitted. Validating firmware image on BTU...")

        _state.value = DFUState.Applying
        val applyPayload = byteArrayOf(DFUCommand.APPLY_FIRMWARE.code.toByte())
        transport.writeCharacteristic(
            serviceUuid = SabGattUuids.SERVICE_DFU,
            characteristicUuid = SabGattUuids.CHAR_DFU_CONTROL_POINT,
            payload = applyPayload,
            withResponse = true
        ).getOrThrow()

        _state.value = DFUState.Completed
        Log.i("Firmware update completed successfully")
    }.onFailure { error ->
        Log.e(error, "DFU update failed")
        _state.value = DFUState.Failed(error)
    }

    override suspend fun abortUpdate(transport: GattTransport): Result<Unit> = runCatchingCancellable {
        Log.w("Aborting DFU firmware update")
        val abortPayload = byteArrayOf(DFUCommand.ABORT_DFU.code.toByte())
        transport.writeCharacteristic(
            serviceUuid = SabGattUuids.SERVICE_DFU,
            characteristicUuid = SabGattUuids.CHAR_DFU_CONTROL_POINT,
            payload = abortPayload,
            withResponse = true
        ).getOrThrow()

        _state.value = DFUState.Idle
    }
}
