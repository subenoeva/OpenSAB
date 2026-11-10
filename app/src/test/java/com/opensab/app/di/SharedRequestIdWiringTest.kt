package com.opensab.app.di

import com.opensab.sab.protocol.assignment.AssignmentReport
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.input.HandlebarEvent
import com.opensab.sab.protocol.projection.CommandId
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.protocol.projection.Receipt
import com.opensab.sab.protocol.projection.ReceiptStatus
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.DefaultSessionPipeline
import com.opensab.sab.session.SabSession
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.assignment.AssignmentManager
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.MockGattTransport
import com.opensab.sab.session.crypto.SecuritySession
import com.opensab.sab.session.crypto.SecuritySessionManager
import com.opensab.sab.session.input.InputDispatcher
import com.opensab.sab.session.input.SabModeFallbacks
import com.opensab.sab.session.input.ScreenGrant
import com.opensab.sab.session.input.SabModeRetry
import com.opensab.sab.session.input.SabModeWatchdog
import com.opensab.sab.session.projection.QueuedPopUp
import com.opensab.sab.session.projection.RenderState
import com.opensab.sab.session.projection.ScreenProjector
import com.opensab.sab.session.vehicle.KnownVehicle
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import com.opensab.sab.session.vehicle.VehicleInfoReader
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring tests for the single shared [RequestIdGenerator].
 *
 * A Receipt is matched to its request by requestId alone. Every writer on the same ControlPoint
 * must therefore draw its requestId from one counter, otherwise concurrent frames collide and
 * receipt correlation breaks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedRequestIdWiringTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)


    @Test
    fun sabModeRequestIdComesFromTheSharedGenerator() = testScope.runTest {
        val generator = RequestIdGenerator(initialValue = 7)
        val inputDispatcher = RecordingInputDispatcher()
        val transport = MockGattTransport().apply { connect() }
        val projector = RecordingScreenProjector()

        val pipeline = DefaultSessionPipeline(
            sessionManager = FakeSecuritySessionManager(),
            vehicleReader = FakeVehicleInfoReader(),
            assignmentManager = FakeAssignmentManager(),
            inputDispatcher = inputDispatcher,
            screenProjector = projector,
            requestIdGenerator = generator,
            capabilitiesHolder = VehicleCapabilitiesHolder(),
            controlPointGateway = com.opensab.sab.session.projection.DefaultControlPointGateway(),
            sabModeWatchdog = SabModeWatchdog(timeout = 10.seconds),
            sabModeRetry = SabModeRetry(),
            onSabModeNotGranted = {},
            sabModeFallbacks = { SabModeFallbacks() },
            activeIndicatorsPublisher = null,
            ctsServer = com.opensab.sab.session.ble.cts.FakeCtsServer(),
            clockSyncEnabled = { true },
            liveLinkHolder = com.opensab.sab.session.LiveSessionLinkHolder(),
            announcer = null,
            storedProfileResolver = null
        )

        // The pipeline alone, on a session built here: the requestId is what this test is about, not
        // who owns the session.
        val session = SabSession(
            id = 1,
            macAddress = "AA:BB:CC:DD:EE:FF",
            origin = SessionOrigin.RECEIVER,
            parent = backgroundScope.coroutineContext,
            stallThreshold = null,
            onCloseRequested = { _, _ -> }
        )
        val result = runCatching { pipeline.open(session, transport) { } }

        assertTrue("Connection pipeline must reach step 7: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(
            "REQUEST_SAB_MODE must draw its requestId from the shared generator, not a literal",
            listOf(7),
            inputDispatcher.requestedSabModeIds
        )
    }

    /** Captures every frame handed to the projector instead of writing it to a transport. */
    private class RecordingScreenProjector : ScreenProjector {

        val frames = mutableListOf<ProjectionFrame>()

        private val _renderState = MutableStateFlow<RenderState>(RenderState.Idle)
        override val renderState: StateFlow<RenderState> = _renderState.asStateFlow()

        override fun start(
            transport: GattTransport,
            modeFlow: StateFlow<DashboardMode>,
            scope: CoroutineScope
        ) = Unit

        override fun stop() = Unit

        override suspend fun updateBaseScreen(frame: ProjectionFrame): Result<Receipt> {
            frames += frame
            return Result.success(
                Receipt(frame.commandId, frame.requestId, ReceiptStatus.SUCCESS)
            )
        }

        override fun invalidateShownBase() = Unit

        override suspend fun postPopUp(popUp: QueuedPopUp): Result<Receipt> =
            Result.failure(UnsupportedOperationException("not used in this test"))

        override suspend fun dismissActivePopUp(): Result<Unit> = Result.success(Unit)

        val indicatorMasks = mutableListOf<Int>()

        override suspend fun updateActiveIndicators(mask: Int): Result<Receipt> {
            indicatorMasks += mask
            return Result.success(
                Receipt(CommandId.ACTIVE_INDICATORS_CONTROL, 0, ReceiptStatus.SUCCESS)
            )
        }
    }

    /** Records the requestId the orchestrator uses when asking the cluster for SAB mode. */
    private class RecordingInputDispatcher : InputDispatcher {

        val requestedSabModeIds = mutableListOf<Int>()

        private val _events = MutableSharedFlow<HandlebarEvent>(extraBufferCapacity = 8)
        override val events: SharedFlow<HandlebarEvent> = _events.asSharedFlow()

        private val _currentMode = MutableStateFlow(DashboardMode.HU_MODE)
        override val currentMode: StateFlow<DashboardMode> = _currentMode.asStateFlow()

        override val screenGrant: StateFlow<ScreenGrant> = MutableStateFlow(ScreenGrant.NONE)

        override fun assumeSabMode(): Boolean = false

        override suspend fun startListening(transport: GattTransport, scope: CoroutineScope) = Unit

        override fun stopListening() = Unit

        override suspend fun requestSabMode(
            transport: GattTransport,
            requestId: Int
        ): Result<Unit> {
            requestedSabModeIds += requestId
            return Result.success(Unit)
        }

        override suspend fun injectMirrorPress(
            functionId: com.opensab.sab.protocol.assignment.FunctionId
        ): Result<Unit> = Result.success(Unit)
    }

    private class FakeSecuritySessionManager : SecuritySessionManager {

        override suspend fun establishSession(
            macAddress: String,
            transport: GattTransport
        ): Result<SecuritySession> =
            Result.success(SecuritySession(macAddress, ByteArray(16)))

        override suspend fun validateExistingKey(
            macAddress: String,
            transport: GattTransport,
            existingKey: ByteArray
        ): Result<SecuritySession> =
            Result.success(SecuritySession(macAddress, existingKey))
    }

    private class FakeVehicleInfoReader : VehicleInfoReader {

        override suspend fun readVehicleIdentity(
            macAddress: String,
            transport: GattTransport,
            stored: com.opensab.sab.session.vehicle.VehicleProfile?
        ): Result<KnownVehicle> = Result.success(
            KnownVehicle(
                macAddress = macAddress,
                displayName = "Honda",
                vehicleInfo = VehicleInformation(
                    vehicleType = 0x01,
                    displayCapability = byteArrayOf(0x01, 0x02),
                    switchType = SwitchType.TYPE_B,
                    categoryId = 0x01,
                    seriesCode = "RH10",
                    destinationId = DestinationId.EU
                )
            )
        )
    }

    private class FakeAssignmentManager : AssignmentManager {
        override suspend fun executeAssignment(
            transport: GattTransport,
            scope: CoroutineScope,
            colorRegistrationNeeded: Boolean
        ): Result<AssignmentReport> =
            Result.success(
                AssignmentReport(written = 0, accepted = 0, rejected = emptyList(), timedOut = emptyList())
            )
    }
}
