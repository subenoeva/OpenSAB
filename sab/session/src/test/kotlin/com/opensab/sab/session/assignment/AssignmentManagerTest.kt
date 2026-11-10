package com.opensab.sab.session.assignment

import com.opensab.sab.protocol.assignment.AssignmentStatus
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.MockGattTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class AssignmentManagerTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    /** Answers every written record with the status the caller picks. */
    private fun MockGattTransport.answerWith(status: Byte) {
        onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_ASSIGNMENT_CONTROL && payload.size >= 3 && payload[0] != 0x03.toByte()) {
                emitNotification(
                    SabGattUuids.SERVICE_ASSIGNMENT,
                    SabGattUuids.CHAR_ASSIGNMENT_CONTROL,
                    byteArrayOf(payload[0], payload[1], payload[2], status)
                )
            }
        }
    }

    @Test
    fun shouldReportEveryRecordAcceptedWhenBtuAnswersSuccess() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        transport.answerWith(0x01)

        val manager: AssignmentManager = DefaultAssignmentManager()
        val result = manager.executeAssignment(transport, backgroundScope)

        assertTrue("Expected assignment to succeed", result.isSuccess)
        val report = result.getOrThrow()
        assertEquals(27, report.written)
        assertEquals(27, report.accepted)
        assertTrue(report.rejected.isEmpty())
        assertTrue(report.timedOut.isEmpty())
        assertTrue(report.isClean)
    }

    @Test
    fun shouldCollectRejectionsWithoutFailingTheSequence() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        transport.answerWith(0x0B)

        val manager: AssignmentManager = DefaultAssignmentManager()
        val result = manager.executeAssignment(transport, backgroundScope)

        assertTrue("Observability block must not abort the pipeline", result.isSuccess)
        val report = result.getOrThrow()
        assertEquals(0, report.accepted)
        assertEquals(27, report.rejected.size)
        assertTrue(report.rejected.all { it.status == AssignmentStatus.INVALID_ID })
        assertTrue(report.isClean.not())
    }

    @Test
    fun shouldTreatAlreadyRegisteredAsAccepted() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        transport.answerWith(0x0D)

        val manager: AssignmentManager = DefaultAssignmentManager()
        val report = manager.executeAssignment(transport, backgroundScope).getOrThrow()

        assertEquals(27, report.accepted)
        assertTrue(report.rejected.isEmpty())
    }

    @Test
    fun shouldRecordTimedOutRequestIdsWhenBtuStaysSilent() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        // No onCharacteristicWrite: the BTU never answers.

        val manager: AssignmentManager = DefaultAssignmentManager(resultTimeout = 10.milliseconds)
        val report = manager.executeAssignment(transport, backgroundScope).getOrThrow()

        assertEquals(27, report.written)
        assertEquals(0, report.accepted)
        assertEquals(27, report.timedOut.size)
        assertEquals(listOf(1, 2, 3), report.timedOut.take(3))
    }

    @Test
    fun shouldRunBlindAndReportAllTimedOutWhenSubscriptionNeverArms() = testScope.runTest {
        val base = MockGattTransport()
        base.connect()
        // AssignmentControl notifications never arm on this hardware: the CCCD write fails.
        val transport = object : GattTransport by base {
            override fun observeNotifications(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                onSubscriptionArmed: (suspend () -> Unit)?
            ): Flow<ByteArray> {
                if (characteristicUuid == SabGattUuids.CHAR_ASSIGNMENT_CONTROL) {
                    return flow { throw IllegalStateException("no CCCD") }
                }
                return base.observeNotifications(serviceUuid, characteristicUuid, onSubscriptionArmed)
            }
        }

        val manager: AssignmentManager = DefaultAssignmentManager()
        val result = manager.executeAssignment(transport, backgroundScope)

        assertTrue("An arming failure is an observation, not an abort", result.isSuccess)
        val report = result.getOrThrow()
        assertEquals(27, report.written)
        assertEquals(0, report.accepted)
        assertTrue(report.rejected.isEmpty())
        assertEquals(27, report.timedOut.size)
        assertTrue(report.isClean.not())
    }

    /**
     * Ending the subscription writes the CCCD back to 00 00, and that write landed around the
     * REQUEST_SAB_MODE that follows the batch. The channel stays armed for the whole session.
     */
    @Test
    fun shouldKeepTheSubscriptionArmedAfterTheBatchAndEndItWithTheSession() = testScope.runTest {
        val base = MockGattTransport()
        base.connect()
        base.answerWith(0x01)
        var live = 0
        val transport = object : GattTransport by base {
            override fun observeNotifications(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                onSubscriptionArmed: (suspend () -> Unit)?
            ): Flow<ByteArray> {
                val notifications = base.observeNotifications(serviceUuid, characteristicUuid, onSubscriptionArmed)
                if (characteristicUuid != SabGattUuids.CHAR_ASSIGNMENT_CONTROL) return notifications
                return notifications.onStart { live++ }.onCompletion { live-- }
            }
        }
        val session = CoroutineScope(coroutineContext + Job())

        val result = DefaultAssignmentManager().executeAssignment(transport, session)

        assertTrue(result.getOrThrow().isClean)
        assertEquals("The batch is over and the channel must still be armed", 1, live)

        session.cancel()
        assertEquals("Closing the session is what ends the subscription", 0, live)
    }

    @Test
    fun shouldWriteEndAssignmentSentinelLastAndOnItsOwn() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        transport.answerWith(0x01)

        val manager: AssignmentManager = DefaultAssignmentManager()
        manager.executeAssignment(transport, backgroundScope)

        val last = transport.readCharacteristic(
            SabGattUuids.SERVICE_ASSIGNMENT,
            SabGattUuids.CHAR_ASSIGNMENT_CONTROL
        ).getOrThrow()

        assertEquals(1, last.size)
        assertEquals(0x03.toByte(), last[0])
    }
}
