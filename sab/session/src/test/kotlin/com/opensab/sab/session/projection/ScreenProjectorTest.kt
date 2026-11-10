package com.opensab.sab.session.projection

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.MockGattTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenProjectorTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun updateBaseScreenProjectsLiveFrameWhenInSabMode() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val modeFlow = MutableStateFlow(DashboardMode.SAB_MODE)
        val projector = DefaultScreenProjector(RequestIdGenerator(), DefaultControlPointGateway())
        projector.start(transport, modeFlow, backgroundScope)

        val liveFrame = TestFrames.music(
            requestId = 1,
            header = "Music",
            title = "Starboy",
            artist = "The Weeknd",
            playing = true
        )

        // Mock BTU auto-receipt emission
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_COMMAND) {
                val cmdId = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0x01
                val reqId = if (payload.size > 1) payload[1].toInt() and 0xFF else 0x01
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(cmdId.toByte(), reqId.toByte(), 0x00) // Receipt SUCCESS
                )
            }
        }

        val result = projector.updateBaseScreen(liveFrame)
        assertTrue("Expected updateBaseScreen success", result.isSuccess)

        val state = projector.renderState.value
        assertTrue(state is RenderState.Projecting)
        assertEquals(liveFrame, (state as RenderState.Projecting).baseFrame)

        projector.stop()
    }

    @Test
    fun shouldFailWhenReceiptNeverArrives() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val modeFlow = MutableStateFlow(DashboardMode.SAB_MODE)
        val projector = DefaultScreenProjector(RequestIdGenerator(), DefaultControlPointGateway())
        projector.start(transport, modeFlow, backgroundScope)

        val liveFrame = TestFrames.music(
            requestId = 1,
            header = "Music",
            title = "Starboy",
            artist = "The Weeknd",
            playing = true
        )

        // BTU does NOT emit any receipt (times out)
        val result = projector.updateBaseScreen(liveFrame)

        assertTrue("Expected failure when receipt times out", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "Expected ProjectionTimeoutException but got $error",
            error is ProjectionTimeoutException
        )

        // State must remain Idle on timeout
        assertEquals(RenderState.Idle, projector.renderState.value)

        projector.stop()
    }

    @Test
    fun shouldFailAndExposeErrorPositionWhenReceiptStatusIsNotZero() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val modeFlow = MutableStateFlow(DashboardMode.SAB_MODE)
        val projector = DefaultScreenProjector(RequestIdGenerator(), DefaultControlPointGateway())
        projector.start(transport, modeFlow, backgroundScope)

        val liveFrame = TestFrames.music(
            requestId = 1,
            header = "Music",
            title = "Starboy",
            artist = "The Weeknd",
            playing = true
        )

        // Mock BTU returning rejection status 0x0C with error position 4
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                val cmdId = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0x01
                val reqId = if (payload.size > 1) payload[1].toInt() and 0xFF else 0x01
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(cmdId.toByte(), reqId.toByte(), 0x0C, 0x04)
                )
            }
        }

        val result = projector.updateBaseScreen(liveFrame)

        assertTrue("Expected failure on rejected receipt", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "Expected ProjectionRejectedException but got $error",
            error is ProjectionRejectedException
        )
        val rejected = error as ProjectionRejectedException
        assertEquals(0x0C, rejected.status)
        assertEquals(4, rejected.errorPosition)

        // Render state must remain Idle
        assertEquals(RenderState.Idle, projector.renderState.value)

        projector.stop()
    }

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
                events.add("observeNotifications")
                return baseTransport.observeNotifications(serviceUuid, characteristicUuid, onSubscriptionArmed)
            }

            override suspend fun writeCharacteristic(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                payload: ByteArray,
                withResponse: Boolean
            ): Result<ByteArray> {
                events.add("writeCharacteristic")
                return baseTransport.writeCharacteristic(serviceUuid, characteristicUuid, payload, withResponse)
            }
        }
        baseTransport.connect()

        val modeFlow = MutableStateFlow(DashboardMode.SAB_MODE)
        val projector = DefaultScreenProjector(RequestIdGenerator(), DefaultControlPointGateway())
        projector.start(transport, modeFlow, backgroundScope)

        val liveFrame = TestFrames.music(
            requestId = 1,
            header = "Music",
            title = "Starboy",
            artist = "The Weeknd",
            playing = true
        )

        // Run updateBaseScreen (will timeout because no receipt emitted, but we check order of subscription vs write)
        projector.updateBaseScreen(liveFrame)

        assertTrue("observeNotifications must precede writeCharacteristic", events.size >= 2)
        assertEquals("observeNotifications", events[0])
        assertEquals("writeCharacteristic", events[1])

        projector.stop()
    }

    @Test
    fun postPopUpAndDismissRestoresBaseScreen() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val modeFlow = MutableStateFlow(DashboardMode.SAB_MODE)
        val projector = DefaultScreenProjector(RequestIdGenerator(), DefaultControlPointGateway())
        projector.start(transport, modeFlow, backgroundScope)

        val liveFrame = TestFrames.music(
            requestId = 1,
            header = "Music",
            title = "Starboy",
            artist = "The Weeknd",
            playing = true
        )

        // Setup mock auto-receipts
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_COMMAND) {
                val cmdId = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0x01
                val reqId = if (payload.size > 1) payload[1].toInt() and 0xFF else 0x01
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(cmdId.toByte(), reqId.toByte(), 0x00)
                )
            }
        }

        projector.updateBaseScreen(liveFrame)

        // Post high priority popup
        val popUp = QueuedPopUp(
            id = "call-1",
            message = "Incoming Call",
            icon = IconId.INCOMING_CALL,
            priority = PopUpPriority.INCOMING_CALL
        )
        val popUpResult = projector.postPopUp(popUp)
        assertTrue("Expected postPopUp success", popUpResult.isSuccess)

        val modalState = projector.renderState.value
        assertTrue(modalState is RenderState.Projecting)
        assertEquals(popUp, (modalState as RenderState.Projecting).activePopUp)

        // Dismiss modal
        val dismissResult = projector.dismissActivePopUp()
        assertTrue("Expected dismissActivePopUp success", dismissResult.isSuccess)

        val restoredState = projector.renderState.value
        assertTrue(restoredState is RenderState.Projecting)
        assertNull((restoredState as RenderState.Projecting).activePopUp)
        assertEquals(liveFrame, restoredState.baseFrame)

        projector.stop()
    }

    /**
     * One command in flight. A frame handed over while another is waiting for its Receipt joins the
     * pending queue and is only written once the BTU has acknowledged the one on the wire.
     */
    @Test
    fun shouldSerializeTwoConcurrentFrames() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val writtenRequestIds = mutableListOf<Int>()
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_COMMAND) {
                writtenRequestIds += payload[1].toInt() and 0xFF
            }
        }

        val modeFlow = MutableStateFlow(DashboardMode.SAB_MODE)
        val projector = DefaultScreenProjector(RequestIdGenerator(), DefaultControlPointGateway())
        projector.start(transport, modeFlow, backgroundScope)

        val first =
            TestFrames.music(10, "Music", title = "First", artist = "A", playing = true)
        val second =
            TestFrames.music(20, "Music", title = "Second", artist = "B", playing = true)

        val firstJob = launch { projector.updateBaseScreen(first) }
        val secondJob = launch { projector.updateBaseScreen(second) }
        runCurrent()

        assertEquals(
            "Only the first frame may be on the wire until its Receipt arrives",
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
            "The queued frame must be written once the first one is acknowledged",
            listOf(10, 20),
            writtenRequestIds
        )

        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
            byteArrayOf(0x01, 20, 0x00)
        )
        runCurrent()

        firstJob.join()
        secondJob.join()
        projector.stop()
    }

    /**
     * The dismiss packet is a projection frame like any other, so it goes through the same queue and
     * its Receipt is awaited and validated.
     */
    @Test
    fun shouldFailWhenDismissReceiptNeverArrives() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val modeFlow = MutableStateFlow(DashboardMode.SAB_MODE)
        val projector = DefaultScreenProjector(RequestIdGenerator(), DefaultControlPointGateway())
        projector.start(transport, modeFlow, backgroundScope)

        // The BTU stays silent: dismissing must surface the timeout instead of reporting success.
        val result = projector.dismissActivePopUp()

        assertTrue("Expected failure when the dismiss Receipt times out", result.isFailure)
        assertTrue(
            "Expected ProjectionTimeoutException but got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is ProjectionTimeoutException
        )

        projector.stop()
    }

    /**
     * ACTIVE_INDICATORS_CONTROL is a fixed 4 byte frame `[0x03, requestId, highByte, bitmask]`, with highByte 0x00 for a non negative mask.
     */
    @Test
    fun shouldWriteTheActiveIndicatorsFrame() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val written = mutableListOf<ByteArray>()
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_COMMAND) {
                written += payload.copyOf()
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(payload[0], payload[1], 0x00)
                )
            }
        }

        val projector = DefaultScreenProjector(RequestIdGenerator(), DefaultControlPointGateway())
        projector.start(transport, MutableStateFlow(DashboardMode.SAB_MODE), backgroundScope)

        val mask = ActiveIndicator.MUSIC.bit or ActiveIndicator.NAVIGATION.bit
        val result = projector.updateActiveIndicators(mask)

        assertTrue("Expected updateActiveIndicators success", result.isSuccess)
        val frame = written.single()
        assertEquals(4, frame.size)
        assertEquals(0x03, frame[0].toInt() and 0xFF)
        assertEquals(0x00, frame[2].toInt() and 0xFF)
        assertEquals(0x06, frame[3].toInt() and 0xFF)

        projector.stop()
    }

    /**
     * The auto-dismiss watchdog belongs to the modal layer (PopupManager), which owns the queue and
     * the retained page. A second timer down here would dismiss the modal behind its back and leave
     * the engine believing a modal is still on screen.
     */
    @Test
    fun shouldNotScheduleItsOwnPopUpAutoDismiss() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()

        val written = mutableListOf<ByteArray>()
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_COMMAND) {
                written += payload.copyOf()
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(payload[0], payload[1], 0x00)
                )
            }
        }

        val projector = DefaultScreenProjector(RequestIdGenerator(), DefaultControlPointGateway())
        projector.start(transport, MutableStateFlow(DashboardMode.SAB_MODE), backgroundScope)

        projector.postPopUp(
            QueuedPopUp(
                id = "incoming-call",
                message = "Alice",
                icon = IconId.INCOMING_CALL,
                priority = PopUpPriority.INCOMING_CALL,
                duration = 5.seconds
            )
        )
        advanceTimeBy(5_001L.milliseconds)

        val dismissFrames = written.filter { it.size == 5 && it[0].toInt() == 0x02 && it[4].toInt() == 0x00 }
        assertTrue("The projector must not dismiss the modal on its own", dismissFrames.isEmpty())

        projector.stop()
    }

    // -------------------------------------------------------------------------------------------
    // POP_UP action block
    // -------------------------------------------------------------------------------------------

    /**
     * A popup that declares nothing special is dismissible on both sides, so both buttons carry
     * POPUP_CANCEL: only a popup that declares them, such as a call, paints POPUP_HANGUP /
     * POPUP_ACCEPT.
     */
    @Test
    fun shouldPaintTheDefaultPopUpActionsAsCancelOnBothSides() = testScope.runTest {
        val frame = projectPopUp(backgroundScope, QueuedPopUp(id = "note", message = "Alice", icon = IconId.INCOMING_MESSAGE))

        assertEquals("Both buttons plus text on a 5 byte message", 22, frame.size)
        val block = frame.copyOfRange(frame.size - ACTION_BLOCK_BYTES, frame.size)
        assertEquals("left action icon", IconId.POPUP_CANCEL.id, block[0].toInt() and 0xFF)
        assertEquals("left action tint", ColorId.NEGATIVE.id, block[1].toInt() and 0xFF)
        assertEquals("left action background", ColorId.NEGATIVE.id, block[4].toInt() and 0xFF)
        assertEquals("right action icon", IconId.POPUP_CANCEL.id, block[5].toInt() and 0xFF)
        assertEquals("right action tint", ColorId.POSITIVE.id, block[6].toInt() and 0xFF)
        assertEquals("right action background", ColorId.POSITIVE.id, block[9].toInt() and 0xFF)
    }

    /**
     * The action block is only present when the left button is, so a popup with no actions has to come out 10 bytes shorter, ending on the colour
     * of its text TLV.
     */
    @Test
    fun shouldOmitTheActionBlockWhenThePopUpDeclaresNoActions() = testScope.runTest {
        val withActions = projectPopUp(backgroundScope, QueuedPopUp(id = "note", message = "Alice", icon = IconId.INCOMING_MESSAGE))
        val withoutActions = projectPopUp(
            backgroundScope,
            QueuedPopUp(
                id = "note",
                message = "Alice",
                icon = IconId.INCOMING_MESSAGE,
                leftActionIcon = null,
                rightActionIcon = null
            )
        )

        assertEquals(
            "A popup with no actions emits no button block",
            withActions.size - ACTION_BLOCK_BYTES,
            withoutActions.size
        )
        // command + requestId + bgColor + icon + iconTint + TLV("Alice") = 12 bytes.
        assertEquals(12, withoutActions.size)
        assertEquals(
            "The frame must end on the text colour",
            ColorId.PURE_WHITE.id,
            withoutActions.last().toInt() and 0xFF
        )
    }

    /** The popup declares its buttons and the projector writes them at the end of the frame. */
    @Test
    fun shouldWriteTheActionIconsThePopUpDeclares() = testScope.runTest {
        val frame = projectPopUp(
            backgroundScope,
            QueuedPopUp(
                id = "call",
                message = "Alice",
                icon = IconId.INCOMING_CALL,
                leftActionIcon = IconId.POPUP_HANGUP,
                rightActionIcon = IconId.POPUP_ACCEPT
            )
        )

        val block = frame.copyOfRange(frame.size - ACTION_BLOCK_BYTES, frame.size)
        assertEquals("left action icon", IconId.POPUP_HANGUP.id, block[0].toInt() and 0xFF)
        assertEquals("right action icon", IconId.POPUP_ACCEPT.id, block[5].toInt() and 0xFF)
    }

    /**
     * Posts [popUp] on a fresh projector and returns the single POP_UP frame that reached the wire.
     */
    /**
     * A quarter of the base frames on the 2026-09-30 ride were byte for byte the previous one with a
     * new requestId: the cluster already shows it, so writing it again only queues the wire.
     */
    @Test
    fun shouldNotWriteABaseFrameIdenticalToTheOneTheClusterShows() = testScope.runTest {
        val (transport, written) = ackingTransport()
        val projector = startedProjector(transport, MutableStateFlow(DashboardMode.SAB_MODE))

        val first = projector.updateBaseScreen(TestFrames.music(1, "Music", title = "A", artist = "B", playing = true))
        val repeat = projector.updateBaseScreen(TestFrames.music(2, "Music", title = "A", artist = "B", playing = true))

        assertTrue(first.isSuccess)
        assertTrue("a skipped repeat is not a failure", repeat.isSuccess)
        assertEquals(listOf(1), written)
        projector.stop()
    }

    @Test
    fun shouldWriteADifferentBaseFrame() = testScope.runTest {
        val (transport, written) = ackingTransport()
        val projector = startedProjector(transport, MutableStateFlow(DashboardMode.SAB_MODE))

        projector.updateBaseScreen(TestFrames.music(1, "Music", title = "A", artist = "B", playing = true))
        projector.updateBaseScreen(TestFrames.music(2, "Music", title = "C", artist = "B", playing = true))

        assertEquals(listOf(1, 2), written)
        projector.stop()
    }

    /** Back from HU_MODE the cluster shows nothing of ours, so the same frame has to go out again. */
    @Test
    fun shouldWriteTheSameBaseFrameAgainAfterTheClusterLeftSabMode() = testScope.runTest {
        val (transport, written) = ackingTransport()
        val mode = MutableStateFlow(DashboardMode.SAB_MODE)
        val projector = startedProjector(transport, mode)

        projector.updateBaseScreen(TestFrames.music(1, "Music", title = "A", artist = "B", playing = true))
        mode.value = DashboardMode.HU_MODE
        runCurrent()
        mode.value = DashboardMode.SAB_MODE
        runCurrent()
        projector.updateBaseScreen(TestFrames.music(2, "Music", title = "A", artist = "B", playing = true))

        assertEquals(listOf(1, 2), written)
        projector.stop()
    }

    /**
     * A frame written before the cluster confirmed the handover may have been discarded, and the
     * mode flow says nothing when that confirmation arrives on top of an assumed grant.
     */
    @Test
    fun shouldWriteTheSameBaseFrameAgainAfterTheShownBaseWasInvalidated() = testScope.runTest {
        val (transport, written) = ackingTransport()
        val projector = startedProjector(transport, MutableStateFlow(DashboardMode.HU_MODE))

        projector.updateBaseScreen(TestFrames.music(1, "Music", title = "A", artist = "B", playing = true))
        projector.invalidateShownBase()
        projector.updateBaseScreen(TestFrames.music(2, "Music", title = "A", artist = "B", playing = true))

        assertEquals(listOf(1, 2), written)
        projector.stop()
    }

    @Test
    fun shouldWriteTheSameBaseFrameAgainAfterAPopUp() = testScope.runTest {
        val (transport, written) = ackingTransport()
        val projector = startedProjector(transport, MutableStateFlow(DashboardMode.SAB_MODE))

        projector.updateBaseScreen(TestFrames.music(1, "Music", title = "A", artist = "B", playing = true))
        projector.postPopUp(QueuedPopUp(id = "p", message = "Hi", icon = IconId.INCOMING_CALL))
        projector.dismissActivePopUp()
        projector.updateBaseScreen(TestFrames.music(2, "Music", title = "A", artist = "B", playing = true))

        assertEquals(2, written.count { it == 1 || it == 2 })
        assertTrue(2 in written)
        projector.stop()
    }

    @Test
    fun shouldWriteTheSameBaseFrameAgainAfterTheBtuRejectedIt() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        val written = mutableListOf<Int>()
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_COMMAND && payload[0].toInt() == 0x01) {
                val reqId = payload[1].toInt() and 0xFF
                written += reqId
                val status: Byte = if (reqId == 1) 0x0C else 0x00
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(0x01, reqId.toByte(), status)
                )
            }
        }
        val projector = startedProjector(transport, MutableStateFlow(DashboardMode.SAB_MODE))

        val rejected = projector.updateBaseScreen(TestFrames.music(1, "Music", title = "A", artist = "B", playing = true))
        projector.updateBaseScreen(TestFrames.music(2, "Music", title = "A", artist = "B", playing = true))

        assertTrue(rejected.isFailure)
        assertEquals(listOf(1, 2), written)
        projector.stop()
    }

    /** Acknowledges every command and records the requestId of each base frame (`0x01`) written. */
    private fun ackingTransport(): Pair<MockGattTransport, List<Int>> {
        val transport = MockGattTransport()
        transport.connect()
        val written = mutableListOf<Int>()
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_COMMAND) {
                val cmdId = payload[0].toInt() and 0xFF
                val reqId = payload[1].toInt() and 0xFF
                if (cmdId == 0x01) written += reqId
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(cmdId.toByte(), reqId.toByte(), 0x00)
                )
            }
        }
        return transport to written
    }

    private fun TestScope.startedProjector(
        transport: MockGattTransport,
        mode: MutableStateFlow<DashboardMode>
    ): DefaultScreenProjector =
        DefaultScreenProjector(RequestIdGenerator(), DefaultControlPointGateway()).also {
            it.start(transport, mode, backgroundScope)
        }

    private suspend fun projectPopUp(scope: CoroutineScope, popUp: QueuedPopUp): ByteArray {
        val transport = MockGattTransport()
        transport.connect()

        val written = mutableListOf<ByteArray>()
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_COMMAND) {
                written += payload.copyOf()
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(payload[0], payload[1], 0x00)
                )
            }
        }

        val projector = DefaultScreenProjector(RequestIdGenerator(), DefaultControlPointGateway())
        projector.start(transport, MutableStateFlow(DashboardMode.SAB_MODE), scope)

        val result = projector.postPopUp(popUp)
        assertTrue("Expected postPopUp success", result.isSuccess)

        projector.stop()
        return written.single()
    }

    private companion object {
        /** Left button (5 B) plus right button (5 B). */
        const val ACTION_BLOCK_BYTES = 10
    }
}
