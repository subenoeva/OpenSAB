package com.opensab.sab.session.projection

import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.projection.CommandId
import com.opensab.sab.protocol.projection.ReceiptStatus
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.MockGattTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ControlPointGatewayTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun shouldSubscribeToNotificationsBeforeWritingTheFrame() = testScope.runTest {
        val events = mutableListOf<String>()
        val baseTransport = MockGattTransport()

        val transport = object : GattTransport by baseTransport {
            override fun observeNotifications(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                onSubscriptionArmed: (suspend () -> Unit)?
            ): Flow<ByteArray> {
                if (characteristicUuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                    events.add("observeNotifications")
                }
                return baseTransport.observeNotifications(serviceUuid, characteristicUuid, onSubscriptionArmed)
            }

            override suspend fun writeCharacteristic(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                payload: ByteArray,
                withResponse: Boolean
            ): Result<ByteArray> {
                if (characteristicUuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                    events.add("writeCharacteristic")
                }
                return baseTransport.writeCharacteristic(serviceUuid, characteristicUuid, payload, withResponse)
            }
        }
        baseTransport.connect()

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)
        runCatching {
            gateway.dispatch(
                transport = transport,
                commandId = CommandId.REQUEST_SAB_MODE,
                requestId = 0x05,
                payload = byteArrayOf(0x0A, 0x05)
            )
        }

        assertTrue("observeNotifications must precede writeCharacteristic on ControlPoint", events.size >= 2)
        assertEquals("observeNotifications", events[0])
        assertEquals("writeCharacteristic", events[1])
    }

    @Test
    fun shouldArmCccdOnlyOnceAcrossMultipleFrameDispatches() = testScope.runTest {
        var observeNotificationsCount = 0
        val baseTransport = MockGattTransport()

        val transport = object : GattTransport by baseTransport {
            override fun observeNotifications(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                onSubscriptionArmed: (suspend () -> Unit)?
            ): Flow<ByteArray> {
                if (characteristicUuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                    observeNotificationsCount++
                }
                return baseTransport.observeNotifications(serviceUuid, characteristicUuid, onSubscriptionArmed)
            }
        }
        baseTransport.connect()
        baseTransport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                val cmdId = payload[0]
                val reqId = payload[1]
                baseTransport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(cmdId, reqId, 0x00)
                )
            }
        }

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)

        assertEquals("CCCD subscribed once upon start", 1, observeNotificationsCount)

        gateway.dispatch(
            transport = transport,
            commandId = CommandId.MYSTERY_BOX,
            requestId = 1,
            payload = byteArrayOf(0x01, 1)
        )

        gateway.dispatch(
            transport = transport,
            commandId = CommandId.MYSTERY_BOX,
            requestId = 2,
            payload = byteArrayOf(0x01, 2)
        )

        gateway.dispatch(
            transport = transport,
            commandId = CommandId.MYSTERY_BOX,
            requestId = 3,
            payload = byteArrayOf(0x01, 3)
        )

        assertEquals(
            "observeNotifications must remain at 1 without re-subscribing on each frame (no CCCD thrashing)",
            1,
            observeNotificationsCount
        )

        gateway.stop()
    }

    @Test
    fun shouldAwaitSubscriptionArmedBeforeCompletingStart() = testScope.runTest {
        val events = mutableListOf<String>()
        val baseTransport = MockGattTransport()

        val transport = object : GattTransport by baseTransport {
            override fun observeNotifications(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                onSubscriptionArmed: (suspend () -> Unit)?
            ): Flow<ByteArray> {
                return kotlinx.coroutines.flow.flow {
                    events.add("arming_started")
                    onSubscriptionArmed?.invoke()
                    events.add("arming_completed")
                    kotlinx.coroutines.awaitCancellation()
                }
            }

            override suspend fun writeCharacteristic(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                payload: ByteArray,
                withResponse: Boolean
            ): Result<ByteArray> {
                events.add("write_frame")
                return Result.success(payload)
            }
        }
        baseTransport.connect()

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)

        assertEquals(
            "Start must await subscription arming",
            listOf("arming_started", "arming_completed"),
            events
        )
        gateway.stop()
    }

    @Test
    fun shouldSucceedWhenReceiptArrivesWithStatusSuccess() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                val cmdId = payload[0].toInt() and 0xFF
                val reqId = payload[1].toInt() and 0xFF
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(cmdId.toByte(), reqId.toByte(), 0x01)
                )
            }
        }

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)
        val receipt = gateway.dispatch(
            transport = transport,
            commandId = CommandId.REQUEST_SAB_MODE,
            requestId = 0x05,
            payload = byteArrayOf(0x0A, 0x05)
        )

        assertEquals(CommandId.REQUEST_SAB_MODE, receipt.commandId)
        assertEquals(0x05, receipt.requestId)
        assertEquals(ReceiptStatus.SUCCESS, receipt.status)
        assertTrue(receipt.isSuccess)
        gateway.stop()
    }

    @Test
    fun shouldFailWhenReceiptNeverArrivesWithWatchdogTimeout() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)
        val result = runCatching {
            gateway.dispatch(
                transport = transport,
                commandId = CommandId.REQUEST_SAB_MODE,
                requestId = 0x05,
                payload = byteArrayOf(0x0A, 0x05)
            )
        }

        assertTrue("Expected failure when receipt times out", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "Expected ProjectionTimeoutException but got $error",
            error is ProjectionTimeoutException
        )
        gateway.stop()
    }

    @Test
    fun shouldFailAndExposeErrorPositionWhenReceiptStatusIsNotZero() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                val cmdId = payload[0].toInt() and 0xFF
                val reqId = payload[1].toInt() and 0xFF
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(cmdId.toByte(), reqId.toByte(), 0x0C, 0x04)
                )
            }
        }

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)
        val result = runCatching {
            gateway.dispatch(
                transport = transport,
                commandId = CommandId.REQUEST_SAB_MODE,
                requestId = 0x05,
                payload = byteArrayOf(0x0A, 0x05)
            )
        }

        assertTrue("Expected failure on rejected receipt", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "Expected ProjectionRejectedException but got $error",
            error is ProjectionRejectedException
        )
        val rejected = error as ProjectionRejectedException
        assertEquals(0x0C, rejected.status)
        assertEquals(4, rejected.errorPosition)
        gateway.stop()
    }

    @Test
    fun shouldSerializeTwoConcurrentDispatches() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val writtenRequestIds = mutableListOf<Int>()
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                writtenRequestIds += payload[1].toInt() and 0xFF
            }
        }

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)

        val firstJob = launch {
            gateway.dispatch(
                transport = transport,
                commandId = CommandId.MYSTERY_BOX,
                requestId = 10,
                payload = byteArrayOf(0x01, 10, 0x00)
            )
        }
        val secondJob = launch {
            gateway.dispatch(
                transport = transport,
                commandId = CommandId.REQUEST_SAB_MODE,
                requestId = 20,
                payload = byteArrayOf(0x0A, 20)
            )
        }
        runCurrent()

        assertEquals(
            "Only the first command may be on the wire until its Receipt arrives",
            listOf(10),
            writtenRequestIds
        )

        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
            byteArrayOf(0x01, 10, 0x00)
        )
        runCurrent()

        assertEquals(
            "The queued SAB_MODE request must be written once the first command is acknowledged",
            listOf(10, 20),
            writtenRequestIds
        )

        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
            byteArrayOf(0x0A, 20, 0x00)
        )
        runCurrent()

        firstJob.join()
        secondJob.join()
        gateway.stop()
    }

    @Test
    fun shouldWriteTwoBytePayloadWithResponseFalse() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        var capturedPayload: ByteArray? = null
        var capturedWithResponse: Boolean? = null
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                capturedPayload = payload
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(payload[0], payload[1], 0x00)
                )
            }
        }

        // Wrap to capture withResponse
        val interceptingTransport = object : GattTransport by transport {
            override suspend fun writeCharacteristic(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                payload: ByteArray,
                withResponse: Boolean
            ): Result<ByteArray> {
                if (characteristicUuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                    capturedWithResponse = withResponse
                }
                return transport.writeCharacteristic(serviceUuid, characteristicUuid, payload, withResponse)
            }
        }

        val gateway = DefaultControlPointGateway()
        gateway.start(interceptingTransport, backgroundScope)
        gateway.dispatch(
            transport = interceptingTransport,
            commandId = CommandId.REQUEST_SAB_MODE,
            requestId = 0x05,
            payload = byteArrayOf(0x0A, 0x05)
        )

        assertArrayEquals(byteArrayOf(0x0A, 0x05), capturedPayload)
        assertEquals(false, capturedWithResponse)
        gateway.stop()
    }
}
