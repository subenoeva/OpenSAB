package com.opensab.sab.session

import com.opensab.logging.Log
import com.opensab.logging.LogSink
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.ble.BondFailedException
import com.opensab.sab.session.ble.DeviceBonder
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.MockGattTransport
import com.opensab.sab.session.crypto.SecuritySession
import com.opensab.sab.session.vehicle.KnownVehicle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SabSessionController] proven in isolation, against fakes only. The real pipeline
 * and participants behind it are exercised through `SessionHarness` and `SessionPipelineIntegrationTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SabSessionControllerTest {

    /**
     * `backgroundScope`, never the bare `coroutineContext`: a [SabSession]'s scope carries
     * `SupervisorJob(parent[Job])`, so a session built on the test body's own job becomes a child
     * `runTest` waits for — and a test that leaves one connected (or mid-attempt) at the end hangs
     * with `UncompletedCoroutinesError`.
     */
    private fun TestScope.controller(
        bonder: DeviceBonder = FakeDeviceBonder(),
        links: LinkOpener = FakeLinkOpener(),
        pipeline: SessionPipeline = FakeSessionPipeline(),
        participants: List<SessionParticipant> = emptyList()
    ) = SabSessionController(
        bonder = bonder,
        links = links,
        pipeline = pipeline,
        participants = participants,
        parent = backgroundScope.coroutineContext,
        stallThreshold = null,
        reconnect = ReconnectPolicy.NONE,
        presence = VehiclePresence.Unknown
    )

    @Test
    fun `a start opens one session and publishes its steps then Connected`() = runTest {
        // A StateFlow conflates: the fakes below never suspend, so every Connecting value the
        // controller sets lands and is overwritten before a collector ever gets scheduled to see
        // it. What proves the steps were reported is the pipeline's own record of the calls, not a
        // collected history of `state`.
        val reportedSteps = mutableListOf<ConnectionStep>()
        val pipeline = FakeSessionPipeline(onReport = { reportedSteps += it })
        val controller = controller(pipeline = pipeline)

        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
        runCurrent()

        assertEquals(
            listOf(ConnectionStep.NEGOTIATING_MTU, ConnectionStep.STARTING_ADAPTERS),
            reportedSteps
        )
        assertTrue(controller.state.value is SabSessionState.Connected)
    }

    /**
     * Field-log greps anchor on this exact text; a session that connects silently is invisible
     * to them.
     */
    @Test
    fun `a successful start logs the session opening and its participant count`() = runTest {
        val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sink = LogSink { _, _, message, args ->
            recorded += if (args.isEmpty()) message.orEmpty() else String.format(message.orEmpty(), *args)
        }
        Log.install(sink)
        try {
            val controller = controller(
                participants = listOf(RecordingParticipant("engine"), RecordingParticipant("music"))
            )
            controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
            runCurrent()

            assertTrue(
                "expected an opened line naming the mac and origin, got: $recorded",
                recorded.any {
                    it.contains("opened for") && it.contains(MAC) && it.contains("RECEIVER")
                }
            )
            assertTrue(
                "expected a line counting the two participants by name, got: $recorded",
                recorded.any {
                    it.contains("started 2 participants") && it.contains("engine") && it.contains("music")
                }
            )
        } finally {
            Log.uninstall(sink)
        }
    }

    @Test
    fun `a start for the mac already live is ignored and logged`() = runTest {
        val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sink = LogSink { _, _, message, _ -> recorded += message.orEmpty() }
        Log.install(sink)
        try {
            val bonder = FakeDeviceBonder()
            val controller = controller(bonder = bonder)
            controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
            runCurrent()
            assertTrue(controller.state.value is SabSessionState.Connected)

            controller.submit(SessionCommand.Start(MAC, SessionOrigin.SCANNER, startId = 2))
            runCurrent()

            assertEquals("the second Start must never even try to bond", listOf(MAC), bonder.calls)
            assertTrue(recorded.any { it.contains("ignored") })
        } finally {
            Log.uninstall(sink)
        }
    }

    @Test
    fun `a start while the same mac is still connecting is ignored`() = runTest {
        val bonder = FakeDeviceBonder()
        val pipeline = FakeSessionPipeline(beforeReturning = { awaitCancellation() })
        val controller = controller(bonder = bonder, pipeline = pipeline)

        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
        runCurrent()
        assertTrue(controller.state.value is SabSessionState.Connecting)

        controller.submit(SessionCommand.Start(MAC, SessionOrigin.SCANNER, startId = 2))
        runCurrent()

        assertEquals("only the first Start may ever try to bond", listOf(MAC), bonder.calls)
    }

    @Test
    fun `a start for another mac closes the live session first`() = runTest {
        val pipeline = FakeSessionPipeline()
        val controller = controller(pipeline = pipeline)
        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
        runCurrent()
        assertTrue(controller.state.value is SabSessionState.Connected)

        controller.submit(SessionCommand.Start(OTHER_MAC, SessionOrigin.SCANNER, startId = 2))
        runCurrent()

        assertEquals(listOf(1L, 2L), pipeline.opened)
        assertTrue(controller.state.value is SabSessionState.Connected)
    }

    @Test
    fun `a disconnect during bonding cancels the attempt and never opens the link`() = runTest {
        val bonder = FakeDeviceBonder(behavior = { awaitCancellation() })
        val links = FakeLinkOpener()
        val controller = controller(bonder = bonder, links = links)

        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
        runCurrent()
        assertEquals(
            "still bonding: the pipeline never ran, so state stays Disconnected",
            SabSessionState.Disconnected,
            controller.state.value
        )

        controller.submit(SessionCommand.Disconnect(SessionOrigin.DASHBOARD, startId = 2))
        runCurrent()

        assertEquals(emptyList<String>(), links.calls)
        assertEquals(SabSessionState.Disconnected, controller.state.value)
    }

    @Test
    fun `a link loss and a disconnect arriving together close the session once`() = runTest {
        val pipeline = FakeSessionPipeline()
        val controller = controller(pipeline = pipeline)
        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
        runCurrent()
        val session = pipeline.capturedSession!!
        assertTrue(controller.state.value is SabSessionState.Connected)

        // The link watcher (inside a real SessionPipeline.open) and a racing Disconnect, simulated
        // by calling both paths back to back.
        session.requestClose(CloseReason.LINK_LOST)
        controller.submit(SessionCommand.Disconnect(SessionOrigin.DASHBOARD, startId = 2))
        runCurrent()

        assertEquals(
            "exactly one close must announce itself",
            1,
            pipeline.closingAnnounced.count { it == session.id }
        )
        assertEquals(SabSessionState.Disconnected, controller.state.value)
    }

    /**
     * Field-log greps read the second of the two closes as `Session command … ignored:`.
     * The receiver's DISCONNECT lands after the link watcher's LINK_LOST has already closed the
     * session, and a close dropped without a line is invisible in the field log.
     */
    @Test
    fun `a disconnect after a link loss is logged as ignored`() = runTest {
        recordLog().use { recorded ->
            val pipeline = FakeSessionPipeline()
            val controller = controller(pipeline = pipeline)
            controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
            runCurrent()
            pipeline.capturedSession!!.requestClose(CloseReason.LINK_LOST)
            runCurrent()

            controller.submit(SessionCommand.Disconnect(SessionOrigin.RECEIVER, startId = 2))
            runCurrent()

            assertEquals(
                "got: ${recorded.lines}",
                1,
                recorded.lines.count { it.startsWith("Session command DISCONNECT ignored:") }
            )
        }
    }

    /** The other order of the same race: the DISCONNECT closes first, the link watcher reports late. */
    @Test
    fun `a link loss after a disconnect is logged as ignored`() = runTest {
        recordLog().use { recorded ->
            val pipeline = FakeSessionPipeline()
            val controller = controller(pipeline = pipeline)
            controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
            runCurrent()
            val session = pipeline.capturedSession!!
            controller.submit(SessionCommand.Disconnect(SessionOrigin.RECEIVER, startId = 2))
            runCurrent()

            session.requestClose(CloseReason.LINK_LOST)
            runCurrent()

            assertEquals(
                "got: ${recorded.lines}",
                1,
                recorded.lines.count { it.startsWith("Session command LINK_LOST ignored:") }
            )
        }
    }

    /**
     * The quick ignition off/on of 2026-09-25: the link watcher closes #2, the
     * receiver's START for the same bike follows at once, and #3 opened 6 ms before #2 logged
     * `closed in`. The close has to go through the loop that serializes the open.
     */
    @Test
    fun `a start right after a link loss waits for the old session to finish closing`() = runTest {
        recordLog().use { recorded ->
            val pipeline = FakeSessionPipeline()
            val links = FakeLinkOpener()
            val controller = controller(links = links, pipeline = pipeline)
            controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
            runCurrent()
            val second = pipeline.capturedSession!!
            val releaseClose = CompletableDeferred<Unit>()
            second.onClose("slow-teardown") { releaseClose.await() }

            second.requestClose(CloseReason.LINK_LOST)
            controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 2))
            runCurrent()

            assertEquals("the next session must not open while #${second.id} is closing", 1, links.calls.size)
            assertTrue(recorded.lines.none { it.startsWith("SabSession #${second.id} closed in") })

            releaseClose.complete(Unit)
            runCurrent()

            assertEquals(2, links.calls.size)
            val closedAt = recorded.lines.indexOfFirst { it.startsWith("SabSession #${second.id} closed in") }
            val nextStartAt = recorded.lines.indexOfFirst { it.startsWith("Session command START from RECEIVER (startId=2)") }
            assertTrue("got: ${recorded.lines}", closedAt in 0 until nextStartAt)
            assertTrue(controller.state.value is SabSessionState.Connected)
            assertTrue(pipeline.capturedSession !== second)
        }
    }

    @Test
    fun `a link loss of an older session closes nothing`() = runTest {
        val pipeline = FakeSessionPipeline()
        val controller = controller(pipeline = pipeline)
        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
        runCurrent()
        val firstSession = pipeline.capturedSession!!

        controller.submit(SessionCommand.Start(OTHER_MAC, SessionOrigin.SCANNER, startId = 2))
        runCurrent()
        val secondSession = pipeline.capturedSession!!
        assertTrue(firstSession !== secondSession)
        val announcedSoFar = pipeline.closingAnnounced.count { it == firstSession.id }

        firstSession.requestClose(CloseReason.LINK_LOST)
        runCurrent()

        assertTrue(
            "the live session (the second one) must be unaffected",
            controller.state.value is SabSessionState.Connected
        )
        assertEquals(
            "a stale link-loss on an already-replaced session must not announce again",
            announcedSoFar,
            pipeline.closingAnnounced.count { it == firstSession.id }
        )
    }

    @Test
    fun `a bond failure publishes Error carrying the BondFailedException`() = runTest {
        val failure = BondFailedException(MAC, reason = 3)
        val bonder = FakeDeviceBonder(behavior = { Result.failure(failure) })
        val controller = controller(bonder = bonder)

        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
        runCurrent()

        val state = controller.state.value
        assertTrue(state is SabSessionState.Error)
        assertSame(failure, (state as SabSessionState.Error).cause)
    }

    @Test
    fun `a link that cannot open publishes Error with TransportUnreachableException`() = runTest {
        val underlying = IllegalStateException("no radio")
        val links = FakeLinkOpener(behavior = { Result.failure(underlying) })
        val controller = controller(links = links)

        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
        runCurrent()

        val state = controller.state.value
        assertTrue(state is SabSessionState.Error)
        val cause = (state as SabSessionState.Error).cause
        assertTrue(cause is TransportUnreachableException)
        assertSame(underlying, cause.cause)
    }

    @Test
    fun `a failed pipeline publishes Error and runs the close actions it registered`() = runTest {
        val closeRan = mutableListOf<String>()
        val failure = IllegalStateException("assignment rejected")
        val pipeline = object : SessionPipeline {
            override suspend fun open(
                session: SabSession,
                transport: GattTransport,
                report: (ConnectionStep) -> Unit
            ): SabSessionState.Connected {
                session.onClose("transport") { closeRan += "transport" }
                throw failure
            }
            override fun announceOpened(session: SabSession) = Unit
            override fun announceClosing(session: SabSession) = Unit
        }
        val controller = controller(pipeline = pipeline)

        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
        runCurrent()

        assertEquals(listOf("transport"), closeRan)
        val state = controller.state.value
        assertTrue(state is SabSessionState.Error)
        assertSame(failure, (state as SabSessionState.Error).cause)
    }

    @Test
    fun `two sessions in a row start every participant twice and leave none started`() = runTest {
        val participant = RecordingParticipant("engine")
        val pipeline = FakeSessionPipeline()
        val controller = controller(pipeline = pipeline, participants = listOf(participant))

        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
        runCurrent()
        controller.submit(SessionCommand.Disconnect(SessionOrigin.DASHBOARD, startId = 2))
        runCurrent()
        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 3))
        runCurrent()
        controller.submit(SessionCommand.Disconnect(SessionOrigin.DASHBOARD, startId = 4))
        runCurrent()

        assertEquals(2, participant.startCalls.size)
        assertEquals(2, participant.closeCalls.size)
        assertFalse("no session is left started", participant.started)
    }

    @Test
    fun `the scope a participant received is cancelled when its session closes`() = runTest {
        val participant = RecordingParticipant("engine")
        val pipeline = FakeSessionPipeline()
        val controller = controller(pipeline = pipeline, participants = listOf(participant))

        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
        runCurrent()
        val scope = participant.capturedScope!!
        assertTrue(scope.coroutineContext[Job]!!.isActive)

        controller.submit(SessionCommand.Disconnect(SessionOrigin.DASHBOARD, startId = 2))
        runCurrent()

        assertFalse(scope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun `idle carries the start id of the last command and only fires with nothing live or pending`() = runTest {
        val pipeline = FakeSessionPipeline()
        val controller = controller(pipeline = pipeline)
        val idles = mutableListOf<Int>()
        val collector = launch { controller.idle.collect { idles += it } }
        runCurrent()

        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 7))
        runCurrent()
        assertEquals("still connected: not idle", emptyList<Int>(), idles)

        controller.submit(SessionCommand.Disconnect(SessionOrigin.DASHBOARD, startId = 8))
        runCurrent()

        assertEquals(listOf(8), idles)
        collector.cancel()
    }

    @Test
    fun `a disconnect with no session reports idle at once`() = runTest {
        val controller = controller()
        val idles = mutableListOf<Int>()
        val collector = launch { controller.idle.collect { idles += it } }
        runCurrent()

        controller.submit(SessionCommand.Disconnect(SessionOrigin.RECEIVER, startId = 3))
        runCurrent()

        assertEquals(listOf(3), idles)
        collector.cancel()
    }

    @Test
    fun `session ids grow within the process`() = runTest {
        val pipeline = FakeSessionPipeline()
        val controller = controller(pipeline = pipeline)

        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 1))
        runCurrent()
        controller.submit(SessionCommand.Disconnect(SessionOrigin.DASHBOARD, startId = 2))
        runCurrent()
        controller.submit(SessionCommand.Start(MAC, SessionOrigin.RECEIVER, startId = 3))
        runCurrent()

        assertEquals(listOf(1L, 2L), pipeline.opened)
    }

    // -------------------------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------------------------

    private class FakeDeviceBonder(
        private val behavior: suspend (String) -> Result<Unit> = { Result.success(Unit) }
    ) : DeviceBonder {
        val calls = mutableListOf<String>()
        override suspend fun ensureBonded(macAddress: String): Result<Unit> {
            calls += macAddress
            return behavior(macAddress)
        }
    }

    private class FakeLinkOpener(
        private val behavior: suspend (String) -> Result<GattTransport> =
            { Result.success(MockGattTransport().apply { connect() }) }
    ) : LinkOpener {
        val calls = mutableListOf<String>()
        override suspend fun open(macAddress: String): Result<GattTransport> {
            calls += macAddress
            return behavior(macAddress)
        }
    }

    /**
     * [beforeReturning] runs after every [steps] callback and before the outcome is decided — a
     * pipeline that never returns (`awaitCancellation()`) simulates a session still connecting, the
     * same way a real one would still be inside the SAB handshake.
     */
    private class FakeSessionPipeline(
        private val steps: List<ConnectionStep> = listOf(ConnectionStep.NEGOTIATING_MTU, ConnectionStep.STARTING_ADAPTERS),
        private val beforeReturning: suspend () -> Unit = {},
        private val onReport: (ConnectionStep) -> Unit = {}
    ) : SessionPipeline {
        val opened = mutableListOf<Long>()
        val closingAnnounced = mutableListOf<Long>()
        var capturedSession: SabSession? = null
            private set

        override suspend fun open(
            session: SabSession,
            transport: GattTransport,
            report: (ConnectionStep) -> Unit
        ): SabSessionState.Connected {
            opened += session.id
            capturedSession = session
            steps.forEach { onReport(it); report(it) }
            beforeReturning()
            return SabSessionState.Connected(vehicle = FAKE_VEHICLE, session = FAKE_SECURITY_SESSION)
        }

        override fun announceOpened(session: SabSession) = Unit

        override fun announceClosing(session: SabSession) {
            closingAnnounced += session.id
        }
    }

    /** Formatted lines through the real [Log] facade; [close] uninstalls the sink. */
    private class RecordedLog : LogSink, AutoCloseable {
        val lines: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        override fun log(priority: Int, t: Throwable?, message: String?, args: Array<out Any?>) {
            lines += if (args.isEmpty()) message.orEmpty() else String.format(message.orEmpty(), *args)
        }

        override fun close() = Log.uninstall(this)
    }

    private fun recordLog(): RecordedLog = RecordedLog().also { Log.install(it) }

    private class RecordingParticipant(override val name: String) : SessionParticipant {
        var started = false
            private set
        val startCalls = mutableListOf<Long>()
        val closeCalls = mutableListOf<Long>()
        var capturedScope: CoroutineScope? = null
            private set

        override fun onSessionStarted(session: SabSession) {
            started = true
            startCalls += session.id
            capturedScope = session.scope
        }

        override fun onSessionClosing(session: SabSession) {
            started = false
            closeCalls += session.id
        }
    }

    private companion object {
        const val MAC = "AA:BB:CC:DD:EE:FF"
        const val OTHER_MAC = "11:22:33:44:55:66"

        val FAKE_VEHICLE = KnownVehicle(
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
        val FAKE_SECURITY_SESSION = SecuritySession(MAC, ByteArray(16))
    }
}
