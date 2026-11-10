package com.opensab.app.session

import com.opensab.app.di.OrchestratorModule
import com.opensab.app.source.music.InMemoryFavoriteMediaRepository
import com.opensab.cluster.BaseStateManager
import com.opensab.cluster.ClusterHandoverCoordinator
import com.opensab.cluster.CueSessionAnnouncer
import com.opensab.cluster.DefaultClusterHandoverCoordinator
import com.opensab.cluster.StubPageProvider
import com.opensab.cluster.allRootMenuEntries
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.cluster.music.MusicPageProvider
import com.opensab.cluster.music.volumeControlOn
import com.opensab.cluster.rootmenu.RootMenuItem
import com.opensab.cluster.rootmenu.RootMenuPageProvider
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.audio.CuePlayer
import com.opensab.domain.audio.FakeHeadsetPresence
import com.opensab.domain.audio.NoCuePlayer
import com.opensab.domain.call.CallNotificationBus
import com.opensab.domain.call.DefaultCallManager
import com.opensab.domain.music.DefaultMusicManager
import com.opensab.domain.music.MockMediaControllerAdapter
import com.opensab.domain.nav.DefaultNavigationManager
import com.opensab.domain.nav.NavigationNotificationBus
import com.opensab.domain.settings.FakeSettingsRepository
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.crypto.Crc16Arc
import com.opensab.sab.protocol.crypto.SabCrypto
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.DefaultSessionPipeline
import com.opensab.sab.session.LinkOpener
import com.opensab.sab.session.LiveSessionLinkHolder
import com.opensab.sab.session.ReconnectPolicy
import com.opensab.sab.session.SabSessionController
import com.opensab.sab.session.VehiclePresence
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.SessionCommand
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.SessionParticipant
import com.opensab.sab.session.assignment.AssignmentManager
import com.opensab.sab.session.assignment.DefaultAssignmentManager
import com.opensab.sab.session.ble.DeviceBonder
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.MockGattTransport
import com.opensab.sab.session.ble.cts.FakeCtsServer
import com.opensab.sab.session.crypto.DefaultSecuritySessionManager
import com.opensab.sab.session.crypto.InMemorySecureKeyStore
import com.opensab.sab.session.input.DefaultInputDispatcher
import com.opensab.sab.session.input.SabModeFallbacks
import com.opensab.sab.session.input.SabModeRetry
import com.opensab.sab.session.input.SabModeWatchdog
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.DefaultScreenProjector
import com.opensab.sab.session.storedProfileResolver
import com.opensab.sab.session.vehicle.DefaultVehicleInfoReader
import com.opensab.sab.session.vehicle.InMemoryVehicleRepository
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import com.opensab.sab.session.vehicle.VehicleInfoReader
import com.opensab.sab.session.vehicle.VehicleRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

internal const val SESSION_RIG_MAC = "AA:BB:CC:DD:EE:FF"

internal fun TestScope.sessionIntegrationRig(
    vehicleRepository: VehicleRepository = InMemoryVehicleRepository(),
    fakeCts: FakeCtsServer = FakeCtsServer(),
    capabilitiesHolder: VehicleCapabilitiesHolder = VehicleCapabilitiesHolder(),
    sabModeWatchdog: SabModeWatchdog = SabModeWatchdog(timeout = 10.seconds),
    projectionMirror: ProjectionMirror = ProjectionMirror(),
    weatherSource: com.opensab.domain.weather.WeatherSource =
        com.opensab.domain.weather.MockWeatherSource(),
    messageSource: com.opensab.domain.message.MessageSource =
        com.opensab.domain.message.MockMessageSource(),
    cuePlayer: CuePlayer = NoCuePlayer,
    assignmentManager: AssignmentManager = DefaultAssignmentManager(),
    vehicleReader: VehicleInfoReader = DefaultVehicleInfoReader()
): SessionIntegrationRig {
    val keyStore = InMemorySecureKeyStore()
    val sessionManager = DefaultSecuritySessionManager(keyStore)
    val controlPointGateway = com.opensab.sab.session.projection.DefaultControlPointGateway()
    val inputDispatcher = DefaultInputDispatcher(controlPointGateway)
    val requestIdGenerator = RequestIdGenerator()
    val projector = DefaultScreenProjector(requestIdGenerator, controlPointGateway)
    val indicators = ActiveIndicatorsPublisher(projector)
    val musicManager = DefaultMusicManager(MockMediaControllerAdapter())
    val navManager = DefaultNavigationManager(NavigationNotificationBus())
    val callManager = DefaultCallManager(CallNotificationBus())
    val baseStateManager = BaseStateManager(
        screenProjector = projector,
        requestIdGenerator = requestIdGenerator,
        capabilities = capabilitiesHolder,
        activeIndicators = ActiveIndicatorsPublisher(projector),
        projectionMirror = projectionMirror,
        rootPageFactory = {
            RootMenuPageProvider(
                entries = allRootMenuEntries(),
                pageFactory = { item ->
                    if (item == RootMenuItem.MUSIC) {
                        MusicPageProvider(musicManager.currentState, englishClusterText, volumeControlOn)
                    } else {
                        StubPageProvider("stub", ProjectionFrame.RequestSabMode(1))
                    }
                },
                copy = englishClusterText
            )
        }
    )

    val liveLinkHolder = LiveSessionLinkHolder()
    val pipeline = DefaultSessionPipeline(
        sessionManager = sessionManager,
        vehicleReader = vehicleReader,
        assignmentManager = assignmentManager,
        inputDispatcher = inputDispatcher,
        screenProjector = projector,
        requestIdGenerator = requestIdGenerator,
        capabilitiesHolder = capabilitiesHolder,
        controlPointGateway = controlPointGateway,
        sabModeWatchdog = sabModeWatchdog,
        sabModeRetry = SabModeRetry(),
        onSabModeNotGranted = projectionMirror::publishGrantOverdue,
        sabModeFallbacks = { SabModeFallbacks() },
        activeIndicatorsPublisher = indicators,
        ctsServer = fakeCts,
        clockSyncEnabled = { true },
        liveLinkHolder = liveLinkHolder,
        announcer = CueSessionAnnouncer(cuePlayer, FakeHeadsetPresence(connected = true), settle = Duration.ZERO),
        storedProfileResolver = vehicleRepository.storedProfileResolver()
    )
    val coordinator = DefaultClusterHandoverCoordinator(
        liveLinkHolder = liveLinkHolder,
        inputDispatcher = inputDispatcher,
        requestIdGenerator = requestIdGenerator,
        sabModeWatchdog = sabModeWatchdog,
        projectionMirror = projectionMirror
    )
    val participants = OrchestratorModule.provideSessionParticipants(
        baseStateManager = baseStateManager,
        inputDispatcher = inputDispatcher,
        musicManager = musicManager,
        navManager = navManager,
        callManager = callManager,
        weatherSource = weatherSource,
        messageSource = messageSource,
        indicators = OrchestratorModule.provideIndicatorTranslators(
            musicManager = musicManager,
            navManager = navManager,
            callManager = callManager,
            messageSource = messageSource,
            activeIndicators = indicators
        ),
        vehicleRepository = vehicleRepository,
        settingsRepository = FakeSettingsRepository(),
        favoriteMediaRepository = InMemoryFavoriteMediaRepository()
    )
    return SessionIntegrationRig(pipeline, coordinator, participants, backgroundScope.coroutineContext, testScheduler)
}

/**
 * The orchestrator driven the way production drives it: a real [SabSessionController] opens every
 * session through it as `SessionPipeline` and starts the participants itself. The bond always
 * succeeds and the link is whatever transport [connect] is handed: neither is what these tests are
 * about.
 *
 * **Owner: this file.** It is shared by [SessionPipelineIntegrationTest] and
 * [ClusterHandoverIntegrationTest]: both drive the same real graph, and two copies of it would
 * drift the day one of them changed.
 */
internal class SessionIntegrationRig(
    val pipeline: DefaultSessionPipeline,
    val coordinator: ClusterHandoverCoordinator,
    participants: List<SessionParticipant>,
    parent: CoroutineContext,
    private val scheduler: TestCoroutineScheduler
) {
    @Volatile
    private var transport: GattTransport? = null
    private var nextStartId = 1

    private val controller = SabSessionController(
        bonder = object : DeviceBonder {
            override suspend fun ensureBonded(macAddress: String): Result<Unit> = Result.success(Unit)
        },
        links = { Result.success(checkNotNull(transport)) },
        pipeline = pipeline,
        participants = participants,
        parent = parent,
        stallThreshold = null,
        reconnect = ReconnectPolicy.NONE,
        presence = VehiclePresence.Unknown
    )

    val state: StateFlow<SabSessionState> get() = controller.state

    suspend fun requestClusterHandover(): Result<Unit> = coordinator.requestClusterHandover()

    /** A START for [transport], settled once the controller publishes Connected or Error. */
    suspend fun connect(transport: GattTransport): Result<SabSessionState.Connected> {
        this.transport = transport
        val before = controller.state.value
        controller.submit(SessionCommand.Start(SESSION_RIG_MAC, SessionOrigin.RECEIVER, nextStartId++))
        return when (val settled = controller.state.first {
            it !== before && (it is SabSessionState.Connected || it is SabSessionState.Error)
        }) {
            is SabSessionState.Connected -> {
                // What the participants and the announcer launched on the session scope has run
                // by the time the connection counts as settled, however many of them there are.
                scheduler.runCurrent()
                Result.success(settled)
            }
            is SabSessionState.Error -> Result.failure(settled.cause)
            else -> error("Unreachable: settled on $settled")
        }
    }

    /** A DISCONNECT, settled once nothing is connected or connecting. */
    suspend fun disconnect() {
        controller.submit(SessionCommand.Disconnect(SessionOrigin.DASHBOARD, nextStartId++))
        controller.state.first { it !is SabSessionState.Connected && it !is SabSessionState.Connecting }
    }
}

fun createPrimedTransport(
    seriesCode: String = "0583",
    aesKey: ByteArray = ByteArray(16) { 0x42.toByte() },
    onDataCommWritten: ((ByteArray) -> Unit)? = null
): MockGattTransport {
    val transport = MockGattTransport()
    transport.connect()

    // Setup Mock BTU responses
    // Security
    transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x00))

    // Vehicle Info
    transport.setCharacteristicValue(SabGattUuids.CHAR_VEHICLE_TYPE, byteArrayOf(0x01))
    transport.setCharacteristicValue(SabGattUuids.CHAR_DISPLAY_CAPABILITY, byteArrayOf(0x01, 0x02))
    transport.setCharacteristicValue(SabGattUuids.CHAR_SWITCH_TYPE, byteArrayOf(0x02))
    transport.setCharacteristicValue(SabGattUuids.CHAR_CATEGORY_ID, byteArrayOf(0x01))
    transport.setCharacteristicValue(SabGattUuids.CHAR_SERIES_CODE, hexToLittleEndianBytes(seriesCode))
    transport.setCharacteristicValue(SabGattUuids.CHAR_DESTINATION_ID, byteArrayOf(0x02))

    // Characteristic writes: Security + Data Comm Auto-Receipts
    transport.onCharacteristicWrite = { uuid, payload ->
        if (uuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT) {
            if (payload.size == 129 && payload[0] == 0x02.toByte()) {
                val modulusBytes = payload.copyOfRange(1, 129)
                val spec = RSAPublicKeySpec(BigInteger(1, modulusBytes), 65537.toBigInteger())
                val pubKey = KeyFactory.getInstance("RSA").generatePublic(spec)
                val plainBlock = ByteArray(110) { 0x00.toByte() } + aesKey + Crc16Arc.calculateLe(aesKey)
                transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, SabCrypto.rsaEncrypt(pubKey, plainBlock))
            } else if (payload.size == 18) {
                val valPayload = byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00)
                val encVal = SabCrypto.aesEncryptPkcs7(aesKey, valPayload)
                transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, byteArrayOf(0x01, 0x00) + encVal)
            }
        } else if (uuid == SabGattUuids.CHAR_DATA_COMM_COMMAND) {
            onDataCommWritten?.invoke(payload.clone())
            val cmdId = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0x01
            val reqId = if (payload.size > 1) payload[1].toInt() and 0xFF else 0x01
            transport.emitNotification(
                SabGattUuids.SERVICE_DATA_COMM,
                SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                byteArrayOf(cmdId.toByte(), reqId.toByte(), 0x00)
            )
        }
        if (uuid == SabGattUuids.CHAR_ASSIGNMENT_CONTROL && payload.size >= 3) {
            transport.emitNotification(
                SabGattUuids.SERVICE_ASSIGNMENT,
                SabGattUuids.CHAR_ASSIGNMENT_CONTROL,
                byteArrayOf(payload[0], payload[1], payload[2], 0x01)
            )
        }
    }
    return transport
}

fun hexToLittleEndianBytes(hex: String): ByteArray {
    val value = hex.toInt(16)
    return byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
}

fun vehicleInformation(seriesCode: String = "0583") = VehicleInformation(
    vehicleType = 0x01,
    displayCapability = byteArrayOf(0x01, 0x02),
    switchType = SwitchType.TYPE_B,
    categoryId = 0x01,
    seriesCode = seriesCode,
    destinationId = DestinationId.EU
)
