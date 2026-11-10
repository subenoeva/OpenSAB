package com.opensab.app.di

import com.opensab.cluster.LiveScreenPresets
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.MockGattTransport
import com.opensab.sab.session.projection.AsciiFoldingTextSanitizer
import com.opensab.sab.session.projection.DefaultControlPointGateway
import com.opensab.sab.session.projection.DefaultScreenProjector
import com.opensab.sab.session.projection.VehicleFramePolicy
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import javax.inject.Singleton

/**
 * Wiring tests for the single shared [com.opensab.sab.session.projection.ControlPointGateway].
 *
 * One command in flight on ControlPoint (handle 83).
 * Both [com.opensab.sab.session.projection.ScreenProjector] and [com.opensab.sab.session.input.InputDispatcher]
 * must share the same [com.opensab.sab.session.projection.ControlPointGateway] instance provided as a
 * [Singleton] by [OrchestratorModule], ensuring all frames and mode requests are serialized
 * through a single FIFO queue and mutex.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlPointOwnershipWiringTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    /**
     * Behavioral invariant test:
     * When both InputDispatcher and ScreenProjector share the single ControlPointGateway from
     * OrchestratorModule, an in-flight REQUEST_SAB_MODE and an updateBaseScreen frame must be
     * strictly serialized so that only one command is on the wire at a time.
     */
    @Test
    fun inputDispatcherAndScreenProjectorSerializeAcrossSharedGateway() = testScope.runTest {
        val gateway = OrchestratorModule.provideControlPointGateway()
        val inputDispatcher = OrchestratorModule.provideInputDispatcher(gateway)
        val generator = OrchestratorModule.provideRequestIdGenerator()
        val policy = VehicleFramePolicy(AsciiFoldingTextSanitizer())
        val capabilitiesHolder = VehicleCapabilitiesHolder()
        val screenProjector = OrchestratorModule.provideScreenProjector(generator, gateway, policy, capabilitiesHolder)

        val transport = MockGattTransport()
        transport.connect()

        val writtenPackets = mutableListOf<ByteArray>()
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                writtenPackets += payload.clone()
            }
        }

        val modeFlow = MutableStateFlow(DashboardMode.SAB_MODE)
        screenProjector.start(transport, modeFlow, backgroundScope)
        inputDispatcher.startListening(transport, backgroundScope)

        // 1. Dispatch REQUEST_SAB_MODE (0x0A, requestId = 10)
        val requestJob = launch {
            inputDispatcher.requestSabMode(transport, 10)
        }

        // 2. Dispatch a live screen projection frame concurrently while requestSabMode is in flight
        val liveFrame =
            LiveScreenPresets.music(20, "Music", title = "Song", artist = "Artist", playing = true)
        val frameJob = launch {
            screenProjector.updateBaseScreen(liveFrame)
        }
        runCurrent()

        // Invariant check: only the first command (0x0A, 10) may have been written to the transport
        assertEquals(
            "Only the first command (REQUEST_SAB_MODE) may be on the wire before its Receipt arrives",
            1,
            writtenPackets.size
        )
        assertEquals(0x0A, writtenPackets[0][0].toInt() and 0xFF)
        assertEquals(10, writtenPackets[0][1].toInt() and 0xFF)

        // Emit Receipt for the first command (REQUEST_SAB_MODE)
        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
            byteArrayOf(0x0A, 10, 0x00)
        )
        runCurrent()

        // Invariant check: now the queued projection frame must have been written to the transport
        assertEquals(
            "The queued projection frame must be written once the first command is acknowledged",
            2,
            writtenPackets.size
        )
        assertEquals(liveFrame.commandId.value, writtenPackets[1][0].toInt() and 0xFF)
        assertEquals(20, writtenPackets[1][1].toInt() and 0xFF)

        // Emit Receipt for the second command
        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
            byteArrayOf(liveFrame.commandId.value.toByte(), 20, 0x00)
        )
        runCurrent()

        requestJob.join()
        frameJob.join()

        screenProjector.stop()
        inputDispatcher.stopListening()
    }

    @Test
    fun startingScreenProjectorAfterGatewayKeepsSharedControlPointSubscription() = testScope.runTest {
        var controlPointSubscriptions = 0
        val baseTransport = MockGattTransport()
        val transport = object : GattTransport by baseTransport {
            override fun observeNotifications(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                onSubscriptionArmed: (suspend () -> Unit)?
            ): Flow<ByteArray> {
                if (characteristicUuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                    controlPointSubscriptions++
                }
                return baseTransport.observeNotifications(serviceUuid, characteristicUuid, onSubscriptionArmed)
            }
        }
        baseTransport.connect()

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)
        val projector = DefaultScreenProjector(RequestIdGenerator(), gateway)

        projector.start(transport, MutableStateFlow(DashboardMode.HU_MODE), backgroundScope)
        runCurrent()

        assertEquals(
            "Starting the projector must not restart the shared ControlPoint subscription",
            1,
            controlPointSubscriptions
        )

        projector.stop()
        gateway.stop()
    }

    /**
     * Mechanism guard test:
     * Asserts via reflection that provideControlPointGateway is annotated with @Singleton in OrchestratorModule.
     *
     * Calling provideControlPointGateway() twice directly on the OrchestratorModule object in unit tests
     * would return distinct instances because @Singleton is enforced by Hilt at runtime/graph construction time.
     * This test ensures that the Hilt module preserves the @Singleton binding so Hilt injects the exact same
     * instance into all dependents.
     */
    @Test
    fun provideControlPointGatewayIsAnnotatedWithSingleton() {
        val method = OrchestratorModule::class.java.getDeclaredMethod("provideControlPointGateway")
        assertTrue(
            "OrchestratorModule.provideControlPointGateway must be annotated with @Singleton. " +
                "Without @Singleton, Hilt provides distinct ControlPointGateway instances to " +
                "DefaultScreenProjector and DefaultInputDispatcher, giving each its own queue and mutex, " +
                "breaking 1-command-in-flight FIFO serialization on ControlPoint (handle 83).",
            method.isAnnotationPresent(Singleton::class.java)
        )
    }
}
