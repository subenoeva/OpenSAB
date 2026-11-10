package com.opensab.sab.session

import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.ble.BleTimeoutException
import com.opensab.sab.session.ble.BondFailedException
import com.opensab.sab.session.ble.DeviceBonder
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.MockGattTransport
import com.opensab.sab.session.crypto.SecuritySession
import com.opensab.sab.session.vehicle.KnownVehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The retry [SabSessionController] owns. 2026-10-02 16:43: one ACL_CONNECTED, one attempt, `Could
 * not open a transport`, the service stopped and the motorcycle stayed linked for another 11 s with
 * nobody trying again; the rider had no session that day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SabSessionControllerReconnectTest {

    private val linked = MutableStateFlow(true)
    private val presence = VehiclePresence { linked }
    private val links = ScriptedLinks()
    private val pipeline = RecordingPipeline()
    private val bonder = ScriptedBonder()
    private val idles = mutableListOf<Int>()

    private fun TestScope.controller(policy: ReconnectPolicy = ReconnectPolicy(listOf(2.seconds, 5.seconds))) =
        SabSessionController(
            bonder = bonder,
            links = links,
            pipeline = pipeline,
            participants = emptyList(),
            parent = backgroundScope.coroutineContext,
            stallThreshold = null,
            reconnect = policy,
            presence = presence
        ).also { controller ->
            backgroundScope.launch { controller.idle.collect { idles += it } }
            runCurrent()
        }

    private fun SabSessionController.start(startId: Int = 1) =
        submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId))

    @Test
    fun `a transport that cannot open is tried again while the vehicle stays linked`() = runTest {
        links.failures += UNREACHABLE
        val controller = controller()

        controller.start()
        runCurrent()

        assertEquals(SabSessionState.Connecting(ConnectionStep.WAITING_TO_RETRY), controller.state.value)
        assertEquals("a pending retry keeps the service up", emptyList<Int>(), idles)
        assertEquals(1, links.calls)

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals("not before its backoff", 1, links.calls)
        advanceTimeBy(2)
        runCurrent()

        assertEquals(2, links.calls)
        assertTrue(controller.state.value is SabSessionState.Connected)
    }

    @Test
    fun `a failure with the vehicle no longer linked is final`() = runTest {
        links.failures += UNREACHABLE
        linked.value = false
        val controller = controller()

        controller.start(startId = 4)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(controller.state.value is SabSessionState.Error)
        assertEquals(1, links.calls)
        assertEquals(listOf(4), idles)
    }

    @Test
    fun `the attempts are bounded and the last failure is the one published`() = runTest {
        val last = BleTimeoutException("third")
        links.failures += listOf(UNREACHABLE, BleTimeoutException("second"), last)
        val controller = controller()

        controller.start(startId = 9)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, links.calls)
        advanceTimeBy(4_998)
        runCurrent()
        assertEquals("the second wait is the longer one", 2, links.calls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(3, links.calls)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals("one attempt and two retries, then nothing", 3, links.calls)
        val state = controller.state.value
        assertTrue(state is SabSessionState.Error)
        assertSame(last, (state as SabSessionState.Error).cause.cause)
        assertEquals("idle only once it has given up", listOf(9), idles)
    }

    /** 2026-10-07 21:10:46: ACL_DISCONNECTED arrived 75 ms after the pipeline failed. */
    @Test
    fun `the vehicle leaving ends the wait at once`() = runTest {
        links.failures += UNREACHABLE
        val controller = controller()
        controller.start(startId = 2)
        runCurrent()

        advanceTimeBy(75)
        linked.value = false
        runCurrent()

        assertTrue(controller.state.value is SabSessionState.Error)
        assertEquals(listOf(2), idles)

        linked.value = true
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("an abandoned retry never fires", 1, links.calls)
    }

    @Test
    fun `a disconnect ends the wait and nothing is tried afterwards`() = runTest {
        links.failures += UNREACHABLE
        val controller = controller()
        controller.start(startId = 1)
        runCurrent()

        controller.submit(SessionCommand.Disconnect(SessionOrigin.DASHBOARD, startId = 2))
        runCurrent()

        assertEquals(SabSessionState.Disconnected, controller.state.value)
        assertEquals(listOf(2), idles)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, links.calls)
        assertEquals(SabSessionState.Disconnected, controller.state.value)
    }

    /** A fresh ACL_CONNECTED is better evidence than a timer: it opens now, and only once. */
    @Test
    fun `a start during the wait opens at once and the timer opens nothing more`() = runTest {
        links.failures += UNREACHABLE
        val controller = controller()
        controller.start(startId = 1)
        runCurrent()

        controller.start(startId = 2)
        runCurrent()

        assertEquals(2, links.calls)
        assertTrue(controller.state.value is SabSessionState.Connected)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, links.calls)
        assertEquals("never two sessions at once", 1, pipeline.mostLiveAtOnce)
    }

    /** 2026-10-07 21:10:46: the link died mid-pipeline (`GATT refused the write ... status=201`). */
    @Test
    fun `a failed pipeline is retried only after its session has closed`() = runTest {
        pipeline.failures += IllegalStateException("GATT refused the write")
        val controller = controller()

        controller.start()
        runCurrent()
        assertEquals(SabSessionState.Connecting(ConnectionStep.WAITING_TO_RETRY), controller.state.value)
        assertEquals(listOf("open #1", "close #1"), pipeline.events)

        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(listOf("open #1", "close #1", "open #2"), pipeline.events)
        assertTrue(controller.state.value is SabSessionState.Connected)
        assertEquals(1, pipeline.mostLiveAtOnce)
    }

    /** The rider said no to the pairing dialog; asking again three times is not robustness. */
    @Test
    fun `a bond failure is not retried`() = runTest {
        bonder.failure = BondFailedException(MAC, reason = 3)
        val controller = controller()

        controller.start()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(controller.state.value is SabSessionState.Error)
        assertEquals(1, bonder.calls)
        assertEquals(0, links.calls)
    }

    @Test
    fun `a link lost after connecting is not retried`() = runTest {
        val controller = controller()
        controller.start()
        runCurrent()

        pipeline.lastSession!!.requestClose(CloseReason.LINK_LOST)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(SabSessionState.Disconnected, controller.state.value)
        assertEquals(1, links.calls)
    }

    @Test
    fun `a new start gets the whole budget again`() = runTest {
        links.failures += listOf(UNREACHABLE, UNREACHABLE, UNREACHABLE, UNREACHABLE)
        val controller = controller()
        controller.start(startId = 1)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, links.calls)

        controller.start(startId = 2)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(5, links.calls)
        assertTrue(controller.state.value is SabSessionState.Connected)
    }

    @Test
    fun `with no retries in the policy a failure is final`() = runTest {
        links.failures += UNREACHABLE
        val controller = controller(policy = ReconnectPolicy.NONE)

        controller.start(startId = 3)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(controller.state.value is SabSessionState.Error)
        assertEquals(1, links.calls)
        assertEquals(listOf(3), idles)
    }

    private class ScriptedBonder : DeviceBonder {
        var failure: Throwable? = null
        var calls = 0
        override suspend fun ensureBonded(macAddress: String): Result<Unit> {
            calls++
            return failure?.let { Result.failure(it) } ?: Result.success(Unit)
        }
    }

    /** Fails with the queued failures, in order, then opens. */
    private class ScriptedLinks : LinkOpener {
        val failures = ArrayDeque<Throwable>()
        var calls = 0
        override suspend fun open(macAddress: String): Result<GattTransport> {
            calls++
            val failure = failures.removeFirstOrNull()
            return if (failure != null) Result.failure(failure) else Result.success(MockGattTransport().apply { connect() })
        }
    }

    private class RecordingPipeline : SessionPipeline {
        val failures = ArrayDeque<Throwable>()
        val events = mutableListOf<String>()
        var lastSession: SabSession? = null
        var mostLiveAtOnce = 0
        private var live = 0

        override suspend fun open(
            session: SabSession,
            transport: GattTransport,
            report: (ConnectionStep) -> Unit
        ): SabSessionState.Connected {
            lastSession = session
            events += "open #${session.id}"
            live++
            mostLiveAtOnce = maxOf(mostLiveAtOnce, live)
            session.onClose("transport") {
                live--
                events += "close #${session.id}"
            }
            report(ConnectionStep.NEGOTIATING_MTU)
            failures.removeFirstOrNull()?.let { throw it }
            return SabSessionState.Connected(vehicle = VEHICLE, session = SecuritySession(MAC, ByteArray(16)))
        }

        override fun announceOpened(session: SabSession) = Unit
        override fun announceClosing(session: SabSession) = Unit
    }

    private companion object {
        const val MAC = "02:00:00:00:00:AB"
        val UNREACHABLE = BleTimeoutException("Service discovery")
        val VEHICLE = KnownVehicle(
            macAddress = MAC,
            displayName = "Honda",
            vehicleInfo = VehicleInformation(
                vehicleType = 0x01,
                displayCapability = byteArrayOf(0x01, 0x02),
                switchType = SwitchType.TYPE_B,
                categoryId = 0x01,
                seriesCode = "0583",
                destinationId = DestinationId.EU
            )
        )
    }
}
