package com.opensab.sab.session.projection

import com.opensab.logging.Log
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.projection.CommandId
import com.opensab.sab.protocol.projection.Receipt
import com.opensab.sab.protocol.projection.ReceiptParser
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.coroutines.isCancellationOfTheCaller
import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.Deque
import kotlin.time.Duration.Companion.seconds

/**
 * Single owner of the Data Communication ControlPoint write path.
 *
 * One writer (FIFO queue + Mutex), because the BTU takes one command in flight at a time; one
 * subscription to ControlPoint notifications for the whole session; and each write waits for the
 * Receipt matching its commandId and requestId, with a 2000 ms watchdog.
 */
interface ControlPointGateway {
    suspend fun start(transport: GattTransport, scope: CoroutineScope)
    fun stop()

    suspend fun dispatch(
        transport: GattTransport,
        commandId: CommandId,
        requestId: Int,
        payload: ByteArray
    ): Receipt

    fun clear()
}

class DefaultControlPointGateway : ControlPointGateway {

    private val pendingCommands: Deque<ByteArray> = ArrayDeque()
    private val commandMutex = Mutex()
    private val receiptsFlow = MutableSharedFlow<Receipt>(replay = 64, extraBufferCapacity = 64)
    private var subscriptionJob: Job? = null

    override suspend fun start(transport: GattTransport, scope: CoroutineScope) {
        if (subscriptionJob?.isActive == true) return
        stop()

        val armed = CompletableDeferred<Unit>()
        val job = scope.launch {
            try {
                transport.observeNotifications(
                    serviceUuid = SabGattUuids.SERVICE_DATA_COMM,
                    characteristicUuid = SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    onSubscriptionArmed = { armed.complete(Unit) }
                ).collect { raw ->
                    val receipt = runCatchingCancellable { ReceiptParser.parse(raw) }.getOrNull()
                    if (receipt != null) {
                        receiptsFlow.emit(receipt)
                    }
                }
            } catch (e: Throwable) {
                if (e.isCancellationOfTheCaller()) {
                    Log.d("ControlPoint notifications subscription cancelled")
                    throw e
                }
                Log.w(e, "ControlPoint notifications subscription terminated with error")
                if (!armed.isCompleted) {
                    armed.completeExceptionally(e)
                }
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null && !armed.isCompleted) {
                armed.completeExceptionally(cause)
            }
        }
        subscriptionJob = job
        try {
            withTimeout(2.seconds) { armed.await() }
        } catch (e: Throwable) {
            subscriptionJob?.cancel()
            subscriptionJob = null
            throw e
        }
    }

    override fun stop() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        clear()
    }

    override suspend fun dispatch(
        transport: GattTransport,
        commandId: CommandId,
        requestId: Int,
        payload: ByteArray
    ): Receipt {
        synchronized(pendingCommands) { pendingCommands.addLast(payload) }
        return commandMutex.withLock {
            val queued = synchronized(pendingCommands) {
                pendingCommands.remove(payload)
                pendingCommands.size
            }
            if (queued > 0) {
                Log.d("Dispatching frame with %d more waiting in the queue", queued)
            }
            sendFrameAndWaitForReceipt(
                transport = transport,
                commandId = commandId,
                requestId = requestId,
                payload = payload
            )
        }
    }

    override fun clear() {
        synchronized(pendingCommands) { pendingCommands.clear() }
    }

    private suspend fun sendFrameAndWaitForReceipt(
        transport: GattTransport,
        commandId: CommandId,
        requestId: Int,
        payload: ByteArray
    ): Receipt {
        check(subscriptionJob?.isActive == true) {
            "ControlPointGateway must be started with a session CoroutineScope before dispatching frames"
        }

        transport.writeCharacteristic(
            serviceUuid = SabGattUuids.SERVICE_DATA_COMM,
            characteristicUuid = SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
            payload = payload,
            withResponse = false
        ).getOrThrow()

        val receipt = withTimeoutOrNull(2.seconds) {
            receiptsFlow
                .first { it.commandId == commandId && it.requestId == requestId }
        } ?: throw ProjectionTimeoutException(
            "Watchdog expired waiting for Receipt on command 0x%02X (requestId=$requestId)".format(commandId.value)
        )

        if (commandId == CommandId.REQUEST_SAB_MODE) {
            Log.i(
                "BTU answered SAB_MODE request: requestId=%d, status=0x%02X",
                receipt.requestId,
                receipt.status.code
            )
        }

        if (!receipt.isSuccess) {
            Log.w(
                "BTU rejected projection frame: commandId=0x%02X, requestId=%d, status=0x%02X, errorPosition=%s",
                receipt.commandId.value,
                receipt.requestId,
                receipt.status.code,
                receipt.errorPosition?.toString() ?: "none"
            )
            throw ProjectionRejectedException(
                status = receipt.status.code,
                errorPosition = receipt.errorPosition
            )
        }

        Log.i(
            "BTU acknowledged frame successfully: commandId=0x%02X, requestId=%d",
            receipt.commandId.value,
            receipt.requestId
        )
        return receipt
    }
}
