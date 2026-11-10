package com.opensab.cluster

import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorState
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.CloseReason
import com.opensab.sab.session.LiveSessionLink
import com.opensab.sab.session.LiveSessionLinkHolder
import com.opensab.sab.session.SabSession
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.MockGattTransport
import com.opensab.sab.session.closeForTest
import com.opensab.sab.session.crypto.SecuritySession
import com.opensab.sab.session.input.DefaultInputDispatcher
import com.opensab.sab.session.input.FakeInputDispatcher
import com.opensab.sab.session.input.InputDispatcher
import com.opensab.sab.session.input.SabModeWatchdog
import com.opensab.sab.session.markConnectedForTest
import com.opensab.sab.session.projection.DefaultControlPointGateway
import com.opensab.sab.session.vehicle.KnownVehicle
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClusterHandoverCoordinatorTest {

    private fun createRig(): Rig {
        val linkHolder = LiveSessionLinkHolder()
        val gateway = DefaultControlPointGateway()
        val inputDispatcher = DefaultInputDispatcher(gateway)
        val requestIdGenerator = RequestIdGenerator()
        val watchdog = SabModeWatchdog(timeout = 100.milliseconds)
        val mirror = ProjectionMirror()
        val coordinator = DefaultClusterHandoverCoordinator(
            liveLinkHolder = linkHolder,
            inputDispatcher = inputDispatcher,
            requestIdGenerator = requestIdGenerator,
            sabModeWatchdog = watchdog,
            projectionMirror = mirror
        )
        return Rig(linkHolder, gateway, inputDispatcher, requestIdGenerator, watchdog, mirror, coordinator)
    }

    private data class Rig(
        val linkHolder: LiveSessionLinkHolder,
        val gateway: DefaultControlPointGateway,
        val inputDispatcher: DefaultInputDispatcher,
        val requestIdGenerator: RequestIdGenerator,
        val watchdog: SabModeWatchdog,
        val mirror: ProjectionMirror,
        val coordinator: ClusterHandoverCoordinator
    )

    private fun TestScope.createConnectedSession(transport: MockGattTransport): SabSession {
        val session = SabSession(
            id = 1L,
            macAddress = "AA:BB:CC:DD:EE:FF",
            origin = SessionOrigin.RECEIVER,
            parent = backgroundScope.coroutineContext,
            stallThreshold = null,
            onCloseRequested = { _, _ -> }
        )
        val vehicle = KnownVehicle(
            macAddress = "AA:BB:CC:DD:EE:FF",
            displayName = "Honda CB650R",
            vehicleInfo = VehicleInformation(
                vehicleType = 0x01,
                displayCapability = byteArrayOf(0x01, 0x02),
                switchType = SwitchType.TYPE_B,
                categoryId = 0x01,
                seriesCode = "0583",
                destinationId = DestinationId.EU
            )
        )
        session.markConnectedForTest(
            SabSessionState.Connected(
                vehicle = vehicle,
                session = SecuritySession("AA:BB:CC:DD:EE:FF", ByteArray(16))
            )
        )
        return session
    }

    private fun armAcknowledgeReceipt(transport: MockGattTransport) {
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                val cmdId = payload[0].toInt() and 0xFF
                val reqId = payload[1].toInt() and 0xFF
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(cmdId.toByte(), reqId.toByte(), 0x00)
                )
            }
        }
    }

    @Test
    fun `fails with IllegalStateException when no active session link exists`() = runTest {
        val rig = createRig()
        val result = rig.coordinator.requestClusterHandover()
        assertTrue("Must fail when link is null", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `fails with IllegalStateException when session is closed`() = runTest {
        val rig = createRig()
        val transport = MockGattTransport()
        val session = createConnectedSession(transport)
        session.closeForTest(CloseReason.DISCONNECT)
        rig.linkHolder.set(LiveSessionLink(session, transport))

        val result = rig.coordinator.requestClusterHandover()
        assertTrue("Must fail when session is closed", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `fails with IllegalStateException when session is not connected`() = runTest {
        val rig = createRig()
        val transport = MockGattTransport()
        val session = SabSession(
            id = 1L,
            macAddress = "AA:BB:CC:DD:EE:FF",
            origin = SessionOrigin.RECEIVER,
            parent = backgroundScope.coroutineContext,
            stallThreshold = null,
            onCloseRequested = { _, _ -> }
        )
        rig.linkHolder.set(LiveSessionLink(session, transport))

        val result = rig.coordinator.requestClusterHandover()
        assertTrue("Must fail when session has no connected state", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `successful handover marks mirror and sets watchdog`() = runTest {
        val rig = createRig()
        rig.mirror.publishClusterOwnsScreen()
        val transport = MockGattTransport()
        transport.connect()
        armAcknowledgeReceipt(transport)
        val session = createConnectedSession(transport)
        val link = LiveSessionLink(session, transport)
        rig.linkHolder.set(link)
        rig.gateway.start(transport, backgroundScope)
        rig.inputDispatcher.startListening(transport, backgroundScope)

        val result = rig.coordinator.requestClusterHandover()
        assertTrue("Handover request should succeed", result.isSuccess)
        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = true), rig.mirror.state.value)
        assertNotNull("Watchdog job must be active", link.watchdogJob.get())

        // When the watchdog expires the button comes back, flagged so the dashboard can hint
        advanceTimeBy(150.milliseconds)
        runCurrent()
        assertEquals(
            MirrorState.ClusterOwnsScreen(handoverPending = false, grantOverdue = true),
            rig.mirror.state.value
        )
    }

    /**
     * The rider pressed "Take the screen" inside the opening window and the session then started
     * projecting on an assumed grant. The handover watchdog still finds no 0x09 when it fires, but
     * the mirror is no longer on the tachometer and must not be dragged back to it under an engine
     * that keeps writing.
     */
    @Test
    fun `the watchdog does not put the mirror on the tachometer while the grant is assumed`() = runTest {
        val rig = createRig()
        rig.mirror.publishClusterOwnsScreen()
        val transport = MockGattTransport()
        transport.connect()
        armAcknowledgeReceipt(transport)
        val session = createConnectedSession(transport)
        rig.linkHolder.set(LiveSessionLink(session, transport))
        rig.gateway.start(transport, backgroundScope)
        rig.inputDispatcher.startListening(transport, backgroundScope)
        rig.coordinator.requestClusterHandover()

        rig.inputDispatcher.assumeSabMode()
        val page = MirrorPage(
            pageName = "RootMenu",
            title = "Weather",
            lines = emptyList(),
            accent = ColorId.ORANGE,
            actions = emptyList()
        )
        rig.mirror.publishPage(page, emptyList())
        advanceTimeBy(150.milliseconds)
        runCurrent()

        assertEquals(MirrorState.Projecting(page = page), rig.mirror.state.value)
    }

    @Test
    fun `anti-freeze recovery publishes handover denied on write failure`() = runTest {
        val rig = createRig()
        rig.mirror.publishClusterOwnsScreen()
        val transport = MockGattTransport() // not connected -> write fails
        val session = createConnectedSession(transport)
        val link = LiveSessionLink(session, transport)
        rig.linkHolder.set(link)

        val result = rig.coordinator.requestClusterHandover()
        assertTrue("Write failure must return failure result", result.isFailure)
        assertEquals(
            "Mirror must immediately publish handover denied on failure",
            MirrorState.ClusterOwnsScreen(handoverPending = false),
            rig.mirror.state.value
        )
    }

    @Test
    fun `closing session during handover triggers denied state via cleanup hook`() = runTest {
        val rig = createRig()
        rig.mirror.publishClusterOwnsScreen()
        val transport = MockGattTransport()
        transport.connect()
        armAcknowledgeReceipt(transport)
        val session = createConnectedSession(transport)
        val link = LiveSessionLink(session, transport)
        rig.linkHolder.set(link)
        rig.gateway.start(transport, backgroundScope)
        rig.inputDispatcher.startListening(transport, backgroundScope)

        val result = rig.coordinator.requestClusterHandover()
        assertTrue("Handover request should succeed", result.isSuccess)
        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = true), rig.mirror.state.value)

        session.closeForTest(CloseReason.DISCONNECT)
        runCurrent()
        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = false), rig.mirror.state.value)
    }

    @Test
    fun `a cancelled request propagates instead of reading as a denied handover`() = runTest {
        val mirror = ProjectionMirror()
        mirror.publishClusterOwnsScreen()
        val cancelled = object : InputDispatcher by FakeInputDispatcher() {
            override suspend fun requestSabMode(transport: GattTransport, requestId: Int): Result<Unit> =
                throw CancellationException("session closed mid-request")
        }
        val linkHolder = LiveSessionLinkHolder()
        val coordinator = DefaultClusterHandoverCoordinator(
            liveLinkHolder = linkHolder,
            inputDispatcher = cancelled,
            requestIdGenerator = RequestIdGenerator(),
            sabModeWatchdog = SabModeWatchdog(timeout = 100.milliseconds),
            projectionMirror = mirror
        )
        val transport = MockGattTransport()
        linkHolder.set(LiveSessionLink(createConnectedSession(transport), transport))

        try {
            coordinator.requestClusterHandover()
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = true), mirror.state.value)
        }
    }
}
