package com.opensab.sab.session.projection

import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.protocol.projection.CommandId
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.protocol.projection.Receipt
import com.opensab.sab.protocol.projection.ReceiptStatus
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.ble.MockGattTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ACTIVE_INDICATORS_CONTROL frame carries the whole bitmask, so every indicator has to be OR-ed
 * into a single owner before it reaches the wire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveIndicatorsPublisherTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    /** Records the bitmask of every ACTIVE_INDICATORS_CONTROL frame that reaches the transport. */
    private fun MockGattTransport.recordIndicatorMasks(): MutableList<Int> {
        val masks = mutableListOf<Int>()
        onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_COMMAND) {
                if (payload[0].toInt() == CommandId.ACTIVE_INDICATORS_CONTROL.value) {
                    masks += payload[3].toInt() and 0xFF
                }
                emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(payload[0], payload[1], 0x00)
                )
            }
        }
        return masks
    }

    private fun startedProjector(transport: MockGattTransport, scope: kotlinx.coroutines.CoroutineScope): ScreenProjector {
        val projector = DefaultScreenProjector(RequestIdGenerator(), DefaultControlPointGateway())
        projector.start(transport, MutableStateFlow(DashboardMode.SAB_MODE), scope)
        return projector
    }


    @Test
    fun combinesEveryIndicatorIntoASingleMask() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        val masks = transport.recordIndicatorMasks()
        val publisher = ActiveIndicatorsPublisher(startedProjector(transport, backgroundScope))

        publisher.set(ActiveIndicator.MUSIC, true)
        publisher.set(ActiveIndicator.NAVIGATION, true)

        assertEquals(listOf(0x04, 0x06), masks)
    }

    @Test
    fun clearsTheBitWhenAnIndicatorGoesOff() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        val masks = transport.recordIndicatorMasks()
        val publisher = ActiveIndicatorsPublisher(startedProjector(transport, backgroundScope))

        publisher.set(ActiveIndicator.PHONE, true)
        publisher.set(ActiveIndicator.MUSIC, true)
        publisher.set(ActiveIndicator.PHONE, false)

        assertEquals(listOf(0x01, 0x05, 0x04), masks)
    }

    @Test
    fun doesNotEmitWhenTheMaskDoesNotChange() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        val masks = transport.recordIndicatorMasks()
        val publisher = ActiveIndicatorsPublisher(startedProjector(transport, backgroundScope))

        // The adapters re-publish on every state change of their source, so an unchanged mask must
        // not spend a write on the shared ControlPoint.
        publisher.set(ActiveIndicator.MUTE, false)
        publisher.set(ActiveIndicator.MUSIC, true)
        publisher.set(ActiveIndicator.MUSIC, true)

        assertEquals(listOf(0x04), masks)
    }

    // -----------------------------------------------------------------------------------------
    // Reactive backing
    // -----------------------------------------------------------------------------------------

    /**
     * The mirror paints the status bar from a flow, so the enabled set has to be observable. The
     * keepalive still reads the mask getter on every tick.
     */
    @Test
    fun `state emits the enabled set after each toggle`() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        transport.recordIndicatorMasks()
        val publisher = ActiveIndicatorsPublisher(startedProjector(transport, backgroundScope))

        assertEquals(emptySet<ActiveIndicator>(), publisher.state.value)

        publisher.set(ActiveIndicator.MUSIC, true)
        assertEquals(setOf(ActiveIndicator.MUSIC), publisher.state.value)

        publisher.set(ActiveIndicator.NAVIGATION, true)
        assertEquals(setOf(ActiveIndicator.MUSIC, ActiveIndicator.NAVIGATION), publisher.state.value)

        publisher.set(ActiveIndicator.MUSIC, false)
        assertEquals(setOf(ActiveIndicator.NAVIGATION), publisher.state.value)
    }

    @Test
    fun `state agrees with the mask that reaches the cluster`() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        transport.recordIndicatorMasks()
        val publisher = ActiveIndicatorsPublisher(startedProjector(transport, backgroundScope))

        publisher.set(ActiveIndicator.PHONE, true)
        publisher.set(ActiveIndicator.MESSAGE, true)

        assertEquals(ActiveIndicator.maskOf(publisher.state.value), publisher.mask)
        assertEquals(0x09, publisher.mask)
    }

    @Test
    fun `an unchanged mask neither re-emits nor writes`() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        val masks = transport.recordIndicatorMasks()
        val publisher = ActiveIndicatorsPublisher(startedProjector(transport, backgroundScope))
        publisher.set(ActiveIndicator.MUSIC, true)

        val emissions = mutableListOf<Set<ActiveIndicator>>()
        val collector = backgroundScope.launch { publisher.state.collect { emissions += it } }

        publisher.set(ActiveIndicator.MUSIC, true)
        collector.cancel()

        assertEquals("Only the state already in force", 1, emissions.size)
        assertEquals(listOf(0x04), masks)
    }

    @Test
    fun `clear drops every indicator without writing to the cluster`() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        val masks = transport.recordIndicatorMasks()
        val publisher = ActiveIndicatorsPublisher(startedProjector(transport, backgroundScope))
        publisher.set(ActiveIndicator.MUSIC, true)

        publisher.clear()

        assertEquals(emptySet<ActiveIndicator>(), publisher.state.value)
        assertEquals(0, publisher.mask)
        assertEquals("Session over: nothing more reaches the wire", listOf(0x04), masks)
    }

    @Test
    fun `forget starts the next session from an empty mask`() = testScope.runTest {
        val transport = MockGattTransport()
        transport.connect()
        val masks = transport.recordIndicatorMasks()
        val publisher = ActiveIndicatorsPublisher(startedProjector(transport, backgroundScope))

        publisher.set(ActiveIndicator.PHONE, true)
        publisher.forget()
        publisher.set(ActiveIndicator.PHONE, true)

        assertEquals(
            "the second session's first PHONE also projects: forget zeroed the mask in between",
            listOf(0x01, 0x01),
            masks
        )
    }

    /**
     * Owning the value is not owning the order.
     *
     * The mask was computed under a lock and written outside it, so two adapters toggling at once —
     * music starting while a call comes in, which is the ordinary case — could compute 0x04 then
     * 0x05 and enqueue the writes the other way round. Since an unchanged mask is never re-sent, on
     * purpose, nothing repairs it afterwards.
     */
    @Test
    fun `projects masks in the order they were computed`() = testScope.runTest {
        val projector = GatedIndicatorProjector()
        val publisher = ActiveIndicatorsPublisher(projector)
        projector.gateNextWrite()

        val first = launch { publisher.set(ActiveIndicator.MUSIC, true) }
        runCurrent() // parked inside the write, holding the mask it computed
        val second = launch { publisher.set(ActiveIndicator.PHONE, true) }
        runCurrent()

        projector.releaseGatedWrite()
        first.join()
        second.join()

        assertEquals(
            "Music alone first, then music and phone: the order the masks were computed in",
            listOf(0x04, 0x05),
            projector.masks
        )
    }

    /**
     * The consequence, stated on its own because it is the part that never heals: the phone would
     * keep an icon lit on the cluster that the app believes it turned off, for the rest of the ride.
     */
    @Test
    fun `an indicator turned off while another write is in flight really goes out`() = testScope.runTest {
        val projector = GatedIndicatorProjector()
        val publisher = ActiveIndicatorsPublisher(projector)
        publisher.set(ActiveIndicator.PHONE, true)
        projector.gateNextWrite()

        val lighting = launch { publisher.set(ActiveIndicator.MUSIC, true) }
        runCurrent()
        val clearing = launch { publisher.set(ActiveIndicator.PHONE, false) }
        runCurrent()

        projector.releaseGatedWrite()
        lighting.join()
        clearing.join()

        assertEquals(
            "The last mask on the wire has to be the one the publisher believes it left",
            publisher.mask,
            projector.masks.last()
        )
        assertEquals("Phone off, music on", 0x04, projector.masks.last())
    }

    /**
     * Parks the first write it is given until released, so a second caller can be let through in
     * between. The transport doubles cannot do this: `MockGattTransport` runs its write callback
     * inside a `runBlocking`, so parking there would block the only test thread.
     */
    private class GatedIndicatorProjector : ScreenProjector {

        val masks = mutableListOf<Int>()

        /** Armed by [gateNextWrite]; the next write takes it and parks on it. */
        private var armed: CompletableDeferred<Unit>? = null

        /** The gate a parked write is actually holding, so releasing it reaches that write. */
        private var held: CompletableDeferred<Unit>? = null

        fun gateNextWrite() {
            armed = CompletableDeferred()
        }

        fun releaseGatedWrite() {
            held?.complete(Unit)
            held = null
        }

        override val renderState: StateFlow<RenderState> = MutableStateFlow(RenderState.Idle)

        override fun start(
            transport: com.opensab.sab.session.ble.GattTransport,
            modeFlow: StateFlow<DashboardMode>,
            scope: kotlinx.coroutines.CoroutineScope
        ) = Unit

        override fun stop() = Unit

        override suspend fun updateBaseScreen(frame: ProjectionFrame): Result<Receipt> =
            Result.failure(UnsupportedOperationException("not part of this test"))

        override fun invalidateShownBase() = Unit

        override suspend fun postPopUp(popUp: QueuedPopUp): Result<Receipt> =
            Result.failure(UnsupportedOperationException("not part of this test"))

        override suspend fun dismissActivePopUp(): Result<Unit> = Result.success(Unit)

        override suspend fun updateActiveIndicators(mask: Int): Result<Receipt> {
            armed?.let { gate ->
                armed = null
                held = gate
                gate.await()
            }
            masks += mask
            return Result.success(
                Receipt(CommandId.ACTIVE_INDICATORS_CONTROL, 0, ReceiptStatus.SUCCESS)
            )
        }
    }
}
