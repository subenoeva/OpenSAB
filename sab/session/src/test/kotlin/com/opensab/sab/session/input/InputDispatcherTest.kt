package com.opensab.sab.session.input

import app.cash.turbine.test
import com.opensab.logging.Log
import com.opensab.logging.LogSink
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.input.HandlebarEvent
import com.opensab.sab.protocol.input.InputSource
import com.opensab.sab.session.ble.MockGattTransport
import com.opensab.sab.session.projection.DefaultControlPointGateway
import com.opensab.sab.session.projection.ProjectionRejectedException
import com.opensab.sab.session.projection.ProjectionTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class InputDispatcherTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val recordedLines = Collections.synchronizedList(mutableListOf<String>())
    private val sink = LogSink { _, _, message, args ->
        recordedLines += if (args.isEmpty()) message.orEmpty() else String.format(message.orEmpty(), *args)
    }

    @Before
    fun installSink() {
        Log.install(sink)
    }

    @After
    fun uninstallSink() {
        Log.uninstall(sink)
    }

    @Test
    fun buttonNotificationEmitsButtonPressedEventThroughTurbine() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        dispatcher.startListening(transport, backgroundScope)

        dispatcher.events.test {
            // Simulate physical button press on handlebar (SELECT = 0x04)
            transport.emitNotification(
                SabGattUuids.SERVICE_DATA_COMM,
                SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
                byteArrayOf(0x04)
            )

            val event = awaitItem()
            assertTrue(event is HandlebarEvent.ButtonPressed)
            assertEquals(FunctionId.SELECT, (event as HandlebarEvent.ButtonPressed).functionId)
            cancelAndIgnoreRemainingEvents()
        }

        dispatcher.stopListening()
    }

    @Test
    fun shouldWriteTwoByteFrameWhenRequestingSabMode() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        var capturedPayload: ByteArray? = null
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                capturedPayload = payload
                val cmdId = payload[0].toInt() and 0xFF
                val reqId = payload[1].toInt() and 0xFF
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(cmdId.toByte(), reqId.toByte(), 0x00)
                )
            }
        }

        val gateway = DefaultControlPointGateway()
        val dispatcher = DefaultInputDispatcher(gateway)
        dispatcher.startListening(transport, backgroundScope)
        val result = dispatcher.requestSabMode(transport, 0x05)

        assertTrue(result.isSuccess)
        assertArrayEquals(byteArrayOf(0x0A, 0x05), capturedPayload)
        dispatcher.stopListening()
        gateway.stop()
    }

    /**
     * The order measured on HCI (2026-08-28) and in `daily-use.log` (2026-09-23 19:41:41): the BTU
     * pushes its current mode 12-15 ms after the CCCD of handle 81 is written, which lands while
     * REQUEST_SAB_MODE is already in flight; then the Receipt; then the 0x09 verdict.
     */
    @Test
    fun modePushedOnSubscriptionIsDeliveredWhileSabRequestIsInFlight() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        var requestWritten = false
        transport.onCharacteristicWrite = { uuid, _ ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) requestWritten = true
        }

        val gateway = DefaultControlPointGateway()
        val dispatcher = DefaultInputDispatcher(gateway)
        dispatcher.startListening(transport, backgroundScope)

        dispatcher.events.test {
            val requestJob = backgroundScope.launch { dispatcher.requestSabMode(transport, 0x01) }
            runCurrent()
            assertTrue("REQUEST_SAB_MODE must be on the wire before the push arrives", requestWritten)

            transport.emitNotification(
                SabGattUuids.SERVICE_DATA_COMM,
                SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
                byteArrayOf(0x0A)
            )
            assertEquals(DashboardMode.HU_MODE, (awaitItem() as HandlebarEvent.ModeChanged).mode)
            assertEquals(DashboardMode.HU_MODE, dispatcher.currentMode.value)

            transport.emitNotification(
                SabGattUuids.SERVICE_DATA_COMM,
                SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                byteArrayOf(0x0A, 0x01, 0x00)
            )
            runCurrent()
            assertTrue(requestJob.isCompleted)
            assertEquals("The Receipt is not the verdict", DashboardMode.HU_MODE, dispatcher.currentMode.value)

            transport.emitNotification(
                SabGattUuids.SERVICE_DATA_COMM,
                SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
                byteArrayOf(0x09)
            )
            assertEquals(DashboardMode.SAB_MODE, (awaitItem() as HandlebarEvent.ModeChanged).mode)
            assertEquals(DashboardMode.SAB_MODE, dispatcher.currentMode.value)
            assertTrue(recordedLines.none { it.startsWith("Discarding") })
            cancelAndIgnoreRemainingEvents()
        }
        dispatcher.stopListening()
        gateway.stop()
    }

    @Test
    fun shouldEnterSabModeWhenNotificationNineArrives() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        dispatcher.startListening(transport, backgroundScope)

        assertEquals(DashboardMode.HU_MODE, dispatcher.currentMode.value)

        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
            byteArrayOf(0x09)
        )

        assertEquals(DashboardMode.SAB_MODE, dispatcher.currentMode.value)
        dispatcher.stopListening()
    }

    @Test
    fun shouldLeaveSabModeWhenNotificationTenArrivesAfterPendingCleared() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        dispatcher.startListening(transport, backgroundScope)

        // Enter SAB_MODE
        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
            byteArrayOf(0x09)
        )
        assertEquals(DashboardMode.SAB_MODE, dispatcher.currentMode.value)

        // Rider switches cluster back to native HU_MODE (0x0A) without pending SAB request
        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
            byteArrayOf(0x0A)
        )

        assertEquals(DashboardMode.HU_MODE, dispatcher.currentMode.value)
        dispatcher.stopListening()
    }

    @Test
    fun shouldSubscribeToNotificationsBeforeWritingSabMode() = testScope.runTest {
        val events = mutableListOf<String>()
        val baseTransport = MockGattTransport()

        val transport = object : com.opensab.sab.session.ble.GattTransport by baseTransport {
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

        baseTransport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                baseTransport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(payload[0], payload[1], 0x00)
                )
            }
        }

        val gateway = DefaultControlPointGateway()
        val dispatcher = DefaultInputDispatcher(gateway)
        dispatcher.startListening(transport, backgroundScope)
        dispatcher.requestSabMode(transport, 0x05)

        assertTrue("observeNotifications must precede writeCharacteristic on ControlPoint", events.size >= 2)
        assertEquals("observeNotifications", events[0])
        assertEquals("writeCharacteristic", events[1])
        dispatcher.stopListening()
        gateway.stop()
    }

    @Test
    fun startListeningSuspendsUntilSubscriptionIsArmed() = testScope.runTest {
        val events = mutableListOf<String>()
        val baseTransport = MockGattTransport()
        val armedTrigger = kotlinx.coroutines.CompletableDeferred<Unit>()

        val transport = object : com.opensab.sab.session.ble.GattTransport by baseTransport {
            override fun observeNotifications(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                onSubscriptionArmed: (suspend () -> Unit)?
            ): Flow<ByteArray> {
                if (characteristicUuid != SabGattUuids.CHAR_FUNCTION_NOTIFICATION) {
                    return baseTransport.observeNotifications(serviceUuid, characteristicUuid, onSubscriptionArmed)
                }
                return kotlinx.coroutines.flow.flow {
                    events.add("arming_started")
                    armedTrigger.await()
                    onSubscriptionArmed?.invoke()
                    events.add("armed_invoked")
                    // Keep flow alive emitting nothing
                    kotlinx.coroutines.awaitCancellation()
                }
            }
        }
        baseTransport.connect()

        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        var startListeningCompleted = false

        val job = launch {
            dispatcher.startListening(transport, backgroundScope)
            startListeningCompleted = true
        }
        runCurrent()

        assertEquals("Subscription arming must have started", listOf("arming_started"), events)
        assertEquals("startListening must not return before subscription is armed", false, startListeningCompleted)

        // Trigger arming
        armedTrigger.complete(Unit)
        runCurrent()

        assertEquals("startListening must complete once armed", true, startListeningCompleted)
        assertEquals(listOf("arming_started", "armed_invoked"), events)

        job.cancel()
        dispatcher.stopListening()
    }

    @Test
    fun startListeningPropagatesSubscriptionFailure() = testScope.runTest {
        val baseTransport = MockGattTransport()
        val transport = object : com.opensab.sab.session.ble.GattTransport by baseTransport {
            override fun observeNotifications(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                onSubscriptionArmed: (suspend () -> Unit)?
            ): Flow<ByteArray> {
                return kotlinx.coroutines.flow.flow {
                    throw com.opensab.sab.session.ble.BleDisconnectedException("Transport disconnected during CCCD write")
                }
            }
        }
        baseTransport.connect()

        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        val result = runCatching {
            dispatcher.startListening(transport, backgroundScope)
        }

        assertTrue("startListening must fail if subscription arming throws", result.isFailure)
        assertTrue(result.exceptionOrNull() is com.opensab.sab.session.ble.BleDisconnectedException)
    }

    @Test
    fun startListeningCancelsJobWhenArmedFails() = testScope.runTest {
        val baseTransport = MockGattTransport()
        val transport = object : com.opensab.sab.session.ble.GattTransport by baseTransport {
            override fun observeNotifications(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                onSubscriptionArmed: (suspend () -> Unit)?
            ): Flow<ByteArray> {
                return kotlinx.coroutines.flow.flow {
                    throw com.opensab.sab.session.ble.BleDisconnectedException("Transport failed")
                }
            }
        }
        baseTransport.connect()

        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        val result = runCatching {
            dispatcher.startListening(transport, backgroundScope)
        }

        assertTrue("startListening must fail", result.isFailure)
        // Ensure no zombie listener is still emitting or collecting
        dispatcher.stopListening()
    }

    @Test
    fun shouldFailWhenSabModeReceiptTimesOut() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)
        val dispatcher = DefaultInputDispatcher(gateway)
        // No receipt emitted by transport
        val result = dispatcher.requestSabMode(transport, 0x05)

        assertTrue("Expected failure on SAB_MODE receipt timeout", result.isFailure)
        assertTrue(
            "Expected ProjectionTimeoutException but got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is ProjectionTimeoutException
        )
        gateway.stop()
    }

    @Test
    fun shouldSucceedWhenSabModeReceiptStatusIsSuccess() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                val reqId = if (payload.size > 1) payload[1].toInt() and 0xFF else 0x01
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(0x0A, reqId.toByte(), 0x01) // Canonical Honda BTU SUCCESS status = 0x01
                )
            }
        }

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)
        val dispatcher = DefaultInputDispatcher(gateway)
        val result = dispatcher.requestSabMode(transport, 0x05)

        assertTrue("Expected success on SAB_MODE receipt status 0x01", result.isSuccess)
        gateway.stop()
    }

    @Test
    fun shouldFailWhenSabModeReceiptStatusIsRejected() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                val reqId = if (payload.size > 1) payload[1].toInt() and 0xFF else 0x01
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(0x0A, reqId.toByte(), 0x0B) // Rejected status = 0x0B (INVALID_COMMAND)
                )
            }
        }

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)
        val dispatcher = DefaultInputDispatcher(gateway)
        val result = dispatcher.requestSabMode(transport, 0x05)

        assertTrue("Expected failure on SAB_MODE rejected receipt status", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "Expected ProjectionRejectedException but got $error",
            error is ProjectionRejectedException
        )
        assertEquals(0x0B, (error as ProjectionRejectedException).status)
        gateway.stop()
    }

    @Test
    fun shouldNotEnterSabModeOnReceiptAloneWithoutModeNotification() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                val reqId = if (payload.size > 1) payload[1].toInt() and 0xFF else 0x01
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(0x0A, reqId.toByte(), 0x01)
                )
            }
        }

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)
        val dispatcher = DefaultInputDispatcher(gateway)
        dispatcher.startListening(transport, backgroundScope)

        val result = dispatcher.requestSabMode(transport, 0x05)
        runCurrent()

        assertTrue("The 0x0A receipt must still be a success", result.isSuccess)
        assertEquals(
            "A Receipt only acknowledges the command; only a 0x09 notification grants the screen",
            DashboardMode.HU_MODE,
            dispatcher.currentMode.value
        )

        dispatcher.stopListening()
        gateway.stop()
    }

    @Test
    fun shouldResetModeToHuModeOnStopListeningSoAReconnectDoesNotInheritSabMode() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        dispatcher.startListening(transport, backgroundScope)

        // Drive the mode to SAB_MODE with a 0x09 MODE_SAB FunctionNotification.
        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
            byteArrayOf(0x09)
        )
        assertEquals(DashboardMode.SAB_MODE, dispatcher.currentMode.value)

        // Session over: stopListening must drop screen ownership, otherwise this @Singleton keeps
        // reporting SAB_MODE and the next auto-connect skips the watchdog against a cluster that
        // granted nothing this session.
        dispatcher.stopListening()

        assertEquals(DashboardMode.HU_MODE, dispatcher.currentMode.value)
    }

    @Test
    fun shouldEnterSabModeOnlyAfterModeNotificationFollowingAcceptedReceipt() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                val reqId = if (payload.size > 1) payload[1].toInt() and 0xFF else 0x01
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(0x0A, reqId.toByte(), 0x01)
                )
            }
        }

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)
        val dispatcher = DefaultInputDispatcher(gateway)
        dispatcher.startListening(transport, backgroundScope)

        dispatcher.requestSabMode(transport, 0x05)
        runCurrent()
        assertEquals(DashboardMode.HU_MODE, dispatcher.currentMode.value)

        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
            byteArrayOf(0x09)
        )
        runCurrent()

        assertEquals(DashboardMode.SAB_MODE, dispatcher.currentMode.value)

        dispatcher.stopListening()
        gateway.stop()
    }

    // -------------------------------------------------------------------------------------------
    // Synthetic input from the mirror
    // -------------------------------------------------------------------------------------------

    /**
     * `source` is for the log and for telling apart, in diagnostics, a session flown from the phone from one flown
     * from the handlebar.
     */
    @Test
    fun `a parsed press comes from the handlebar`() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        dispatcher.startListening(transport, backgroundScope)

        dispatcher.events.test {
            transport.emitNotification(
                SabGattUuids.SERVICE_DATA_COMM,
                SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
                byteArrayOf(0x04)
            )

            assertEquals(
                InputSource.HANDLEBAR,
                (awaitItem() as HandlebarEvent.ButtonPressed).source
            )
            cancelAndIgnoreRemainingEvents()
        }

        dispatcher.stopListening()
    }

    @Test
    fun `injectMirrorPress emits a synthetic press once the cluster granted SAB_MODE`() =
        testScope.runTest {
            val transport = MockGattTransport()
            transport.connect()
            val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
            dispatcher.startListening(transport, backgroundScope)
            transport.emitNotification(
                SabGattUuids.SERVICE_DATA_COMM,
                SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
                byteArrayOf(0x09)
            )
            runCurrent()

            dispatcher.events.test {
                val result = dispatcher.injectMirrorPress(FunctionId.SELECT)

                assertTrue(result.isSuccess)
                val event = awaitItem() as HandlebarEvent.ButtonPressed
                assertEquals(FunctionId.SELECT, event.functionId)
                assertEquals(InputSource.MIRROR, event.source)
                cancelAndIgnoreRemainingEvents()
            }

            dispatcher.stopListening()
        }

    /**
     * The third of three guards for the same case — the UI disables the zones and
     * `BaseStateManager.onButtonPressed` already drops presses outside SAB_MODE. It stays because
     * the failure it prevents, a frame written while the cluster paints the tachometer, is not
     * observable from the app.
     */
    @Test
    fun `injectMirrorPress is refused while the cluster owns the screen`() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        dispatcher.startListening(transport, backgroundScope)
        assertEquals(DashboardMode.HU_MODE, dispatcher.currentMode.value)

        val emitted = mutableListOf<HandlebarEvent>()
        val collector = backgroundScope.launch { dispatcher.events.collect { emitted += it } }
        runCurrent()

        val result = dispatcher.injectMirrorPress(FunctionId.SELECT)
        runCurrent()
        collector.cancel()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("Nothing may reach the engine while the tachometer owns the screen", 0, emitted.size)

        dispatcher.stopListening()
    }

    // --- Screen grant: the mode the BTU reported, plus the one assumption the rider can opt into ---

    private suspend fun MockGattTransport.notifyMode(code: Int) = emitNotification(
        SabGattUuids.SERVICE_DATA_COMM,
        SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
        byteArrayOf(code.toByte())
    )

    private suspend fun TestScope.listeningDispatcher(): Pair<DefaultInputDispatcher, MockGattTransport> {
        val transport = MockGattTransport()
        transport.connect()
        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        dispatcher.startListening(transport, backgroundScope)
        return dispatcher to transport
    }

    @Test
    fun theScreenGrantFollowsTheModeNotifications() = testScope.runTest {
        val (dispatcher, transport) = listeningDispatcher()
        assertEquals(ScreenGrant.NONE, dispatcher.screenGrant.value)

        transport.notifyMode(0x09)
        assertEquals(ScreenGrant.CONFIRMED, dispatcher.screenGrant.value)

        transport.notifyMode(0x0A)
        assertEquals(ScreenGrant.NONE, dispatcher.screenGrant.value)
        dispatcher.stopListening()
    }

    @Test
    fun assumingTheGrantNeverChangesTheModeTheBtuReported() = testScope.runTest {
        val (dispatcher, _) = listeningDispatcher()

        assertTrue(dispatcher.assumeSabMode())

        assertEquals(ScreenGrant.ASSUMED, dispatcher.screenGrant.value)
        assertEquals("Only a 0x09 changes the mode", DashboardMode.HU_MODE, dispatcher.currentMode.value)
        dispatcher.stopListening()
    }

    @Test
    fun aReal0x09ConfirmsAnAssumedGrant() = testScope.runTest {
        val (dispatcher, transport) = listeningDispatcher()
        dispatcher.assumeSabMode()

        transport.notifyMode(0x09)

        assertEquals(ScreenGrant.CONFIRMED, dispatcher.screenGrant.value)
        assertEquals(DashboardMode.SAB_MODE, dispatcher.currentMode.value)
        dispatcher.stopListening()
    }

    /**
     * The assumption is made against a BTU that has been saying HU_MODE all along, and it may say
     * it once more in answer to the request written at the timeout. That tells nothing new.
     */
    @Test
    fun aHuModeNotificationDoesNotRevokeAnAssumedGrant() = testScope.runTest {
        val (dispatcher, transport) = listeningDispatcher()
        dispatcher.assumeSabMode()

        transport.notifyMode(0x0A)

        assertEquals(ScreenGrant.ASSUMED, dispatcher.screenGrant.value)
        dispatcher.stopListening()
    }

    @Test
    fun aHuModeAfterTheRealGrantEndsItEvenIfItWasAssumedFirst() = testScope.runTest {
        val (dispatcher, transport) = listeningDispatcher()
        dispatcher.assumeSabMode()
        transport.notifyMode(0x09)

        transport.notifyMode(0x0A)

        assertEquals("Returning to HU_MODE is the rider's call", ScreenGrant.NONE, dispatcher.screenGrant.value)
        dispatcher.stopListening()
    }

    @Test
    fun theGrantIsNotAssumedOnceTheBtuHasGrantedInThisSession() = testScope.runTest {
        val (dispatcher, transport) = listeningDispatcher()
        transport.notifyMode(0x09)
        transport.notifyMode(0x0A)

        assertFalse("The rider took the screen back: nothing is assumed over that", dispatcher.assumeSabMode())

        assertEquals(ScreenGrant.NONE, dispatcher.screenGrant.value)
        dispatcher.stopListening()
    }

    @Test
    fun stopListeningDropsAnAssumedGrantAndForgetsTheSession() = testScope.runTest {
        val (dispatcher, transport) = listeningDispatcher()
        transport.notifyMode(0x09)
        dispatcher.stopListening()
        assertEquals(ScreenGrant.NONE, dispatcher.screenGrant.value)

        // A second transport: MockGattTransport replays its last notification to a new subscriber,
        // and the 0x09 of the session that just ended is not what a real BTU pushes on connecting.
        val next = MockGattTransport()
        next.connect()
        dispatcher.startListening(next, backgroundScope)
        assertTrue("A new session has seen no grant yet", dispatcher.assumeSabMode())
        dispatcher.stopListening()

        assertEquals(ScreenGrant.NONE, dispatcher.screenGrant.value)
    }

    @Test
    fun `injectMirrorPress is accepted while the grant is assumed`() = testScope.runTest {
        val (dispatcher, _) = listeningDispatcher()
        dispatcher.assumeSabMode()

        dispatcher.events.test {
            assertTrue(dispatcher.injectMirrorPress(FunctionId.SELECT).isSuccess)
            assertEquals(FunctionId.SELECT, (awaitItem() as HandlebarEvent.ButtonPressed).functionId)
            cancelAndIgnoreRemainingEvents()
        }
        dispatcher.stopListening()
    }

    @Test
    fun aHandlebarPressIsLoggedWithAStableAnchor() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        dispatcher.startListening(transport, backgroundScope)

        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
            byteArrayOf(0x04)
        )
        runCurrent()

        assertTrue("got: $recordedLines", recordedLines.contains("Handlebar press SELECT"))
        dispatcher.stopListening()
    }

    @Test
    fun aModeChangeIsNotAHandlebarPress() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        dispatcher.startListening(transport, backgroundScope)

        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
            byteArrayOf(0x09)
        )
        runCurrent()

        assertTrue(recordedLines.any { it.startsWith("Received handlebar event: ModeChanged") })
        assertTrue(recordedLines.none { it.startsWith("Handlebar press") })
        dispatcher.stopListening()
    }
}
