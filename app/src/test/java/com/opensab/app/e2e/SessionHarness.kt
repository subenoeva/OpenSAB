package com.opensab.app.e2e

import androidx.lifecycle.ViewModelStore
import com.opensab.app.ble.GattTransportFactory
import com.opensab.app.di.MessageModule
import com.opensab.app.di.NotificationModule
import com.opensab.app.di.OrchestratorModule
import com.opensab.app.di.SessionModule
import com.opensab.app.service.SessionLauncher
import com.opensab.app.source.music.InMemoryFavoriteMediaRepository
import com.opensab.app.ui.dashboard.DashboardViewModel
import com.opensab.app.ui.permissions.FakePermissionStatusReader
import com.opensab.app.ui.permissions.PermissionCatalog
import com.opensab.app.ui.permissions.PermissionStatus
import com.opensab.app.ui.scan.VehicleScanViewModel
import com.opensab.cluster.BaseStateManager
import com.opensab.cluster.ClusterHandoverCoordinator
import com.opensab.cluster.CueSessionAnnouncer
import com.opensab.cluster.DefaultClusterHandoverCoordinator
import com.opensab.cluster.DefaultPageProviderFactory
import com.opensab.cluster.PageProviderFactory
import com.opensab.cluster.PageVoice
import com.opensab.cluster.indicators.IndicatorTranslator
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.cluster.music.volumeControlOn
import com.opensab.cluster.text.ClusterPresentation
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.WeatherCaptionResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.cluster.text.englishWeatherCaptions
import com.opensab.domain.audio.CuePlayer
import com.opensab.domain.audio.FakeHeadsetPresence
import com.opensab.domain.audio.MockCuePlayer
import com.opensab.domain.audio.NoAudioFocus
import com.opensab.domain.autoconnect.AclPresence
import com.opensab.domain.call.CallManager
import com.opensab.domain.call.CallNotificationDriver
import com.opensab.domain.call.MockCallDialer
import com.opensab.domain.call.RecordingTelecomAdapter
import com.opensab.domain.contacts.MockCallMenuAccess
import com.opensab.domain.contacts.MockContactDirectory
import com.opensab.domain.message.MessageSource
import com.opensab.domain.message.MockMessageMenuAccess
import com.opensab.domain.message.MockSmsSender
import com.opensab.domain.music.MockMediaControllerAdapter
import com.opensab.domain.music.MusicManager
import com.opensab.domain.music.RecordingVolumeSource
import com.opensab.domain.nav.FakeDeviceUnlockSource
import com.opensab.domain.nav.MockNavigationLauncher
import com.opensab.domain.nav.NavigationLauncher
import com.opensab.domain.nav.NavigationManager
import com.opensab.domain.settings.FakeSettingsRepository
import com.opensab.domain.speech.MockSpeechRecognizer
import com.opensab.domain.speech.MockSpeechSynthesizer
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechTextResolver
import com.opensab.domain.weather.MockWeatherSource
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.DefaultSessionPipeline
import com.opensab.sab.session.LinkOpener
import com.opensab.sab.session.LiveSessionLinkHolder
import com.opensab.sab.session.ReconnectPolicy
import com.opensab.sab.session.SabSessionController
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.SabSessions
import com.opensab.sab.session.SessionCommand
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.SessionParticipant
import com.opensab.sab.session.SessionPipeline
import com.opensab.sab.session.assignment.DefaultAssignmentManager
import com.opensab.sab.session.ble.DeviceBonder
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.MockBleScanner
import com.opensab.sab.session.ble.cts.FakeCtsServer
import com.opensab.sab.session.crypto.InMemorySecureKeyStore
import com.opensab.sab.session.input.InputDispatcher
import com.opensab.sab.session.input.SabModeRetry
import com.opensab.sab.session.input.SabModeWatchdog
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.ControlPointGateway
import com.opensab.sab.session.projection.ScreenProjector
import com.opensab.sab.session.projection.VehicleFramePolicy
import com.opensab.sab.session.storedProfileResolver
import com.opensab.sab.session.vehicle.DefaultVehicleInfoReader
import com.opensab.sab.session.vehicle.InMemoryVehicleRepository
import com.opensab.sab.session.vehicle.KnownVehicle
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import com.opensab.sab.session.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A whole OpenSAB session on the JVM: the real graph, built through the very factories Hilt calls.
 *
 * Nothing here re-implements the wiring: tests that built graphs of their own once stayed green
 * while five carousel entries did nothing, because the graph Hilt builds was the one nobody
 * exercised. Only the platform leaves are substituted — media, telephony, volume, CTS, scanner,
 * transport factory and bonder — because those are the seams the app itself defines for that
 * purpose.
 *
 * @param sessionScope the scope the session runs on. Never a ViewModel's: handing `viewModelScope`
 *   to the pipeline is what killed the whole session 800 ms after it connected.
 * @param enginePlayer wraps [cuePlayer] once; the same wrapped instance goes to the engine and to
 *   the session pipeline, matching the one `@Singleton` binding `AudioModule.provideCuePlayer` wires.
 *   A test proving the setting-off path passes `{ GatedCuePlayer(it, UnknownCueOutput) { false } }`,
 *   so asserting on [cuePlayer].played proves the gate cuts before the recorder, for both the
 *   engine's cues and the link's. A lambda because [cuePlayer] does not exist yet at this point.
 */
class SessionHarness(
    private val sessionScope: CoroutineScope,
    val btu: BtuSimulator = BtuSimulator(),
    val clusterResolver: ClusterTextResolver = englishClusterText,
    captionResolver: WeatherCaptionResolver = englishWeatherCaptions,
    enginePlayer: (CuePlayer) -> CuePlayer = { it },
    /**
     * Wraps the real [ScreenProjector] before it reaches the engine and the pipeline, so a test
     * can instrument `updateBaseScreen` (a barrier counting concurrent engine mutations, for
     * instance) without re-wiring the graph itself.
     */
    projectorWrapper: (ScreenProjector) -> ScreenProjector = { it },
    /**
     * How long a link has to last before LINK_UP sounds. Zero unless the test is about the wait:
     * the suite reads the cue right after [connect].
     */
    linkSettle: Duration = Duration.ZERO,
    /** One attempt and no retry unless the test is about the retry. */
    reconnect: ReconnectPolicy = ReconnectPolicy.NONE
) {

    // --- Platform leaves -----------------------------------------------------------------------

    val telecom = RecordingTelecomAdapter()
    val volume = RecordingVolumeSource()
    val media = MockMediaControllerAdapter()
    val cts = FakeCtsServer()
    val scanner = MockBleScanner()
    val vehicles = CountingVehicleRepository()
    val capabilities = VehicleCapabilitiesHolder()
    val bonder = RecordingDeviceBonder()
    val transportFactory = FixedTransportFactory(btu.transport)

    /** What the ACL receiver would have recorded. A test says when the motorcycle is linked. */
    val aclPresence = AclPresence()

    /**
     * No network in the harness: the weather source is the mock, and the carousel entry stays
     * closed exactly as it does on a bike with no forecast yet.
     */
    val weather = MockWeatherSource()

    /** `TextToSpeech` on a phone: a press arrives over the wire and an utterance comes out here. */
    val spoken = MockSpeechSynthesizer()

    /**
     * The resolver without a `Context`, printing the token and its arguments instead of a sentence.
     *
     * Deliberately not the English strings: copying `values/strings.xml` into a test double would
     * assert the translation and drift from it, while this pins the thing the row can actually get
     * wrong — which phrase, with which arguments, in which order.
     */
    val speechResolver = SpeechTextResolver { request ->
        val args = request.args.joinToString(", ") { arg ->
            when (arg) {
                is SpeechArg.Text -> arg.value
                is SpeechArg.Digits -> arg.value
                is SpeechArg.Quantity -> "${arg.count} ${arg.token}"
                is SpeechArg.Word -> arg.token.name
            }
        }
        "${request.token}[$args]"
    }

    /*
     * The cluster's two vocabularies ([clusterResolver], [captionResolver]) are real English, not
     * the token-printing shape [speechResolver] uses: a cluster line is one word and the e2e suite
     * reads those words off the wire (`containsAscii("Weather")` on a MENU frame). That a page
     * takes its words from the resolver at all is checked in the provider tests.
     */

    // --- The graph, as Hilt assembles it -------------------------------------------------------

    val requestIdGenerator: RequestIdGenerator = OrchestratorModule.provideRequestIdGenerator()
    val controlPointGateway: ControlPointGateway = OrchestratorModule.provideControlPointGateway()
    val inputDispatcher: InputDispatcher = OrchestratorModule.provideInputDispatcher(controlPointGateway)
    val screenProjector: ScreenProjector = projectorWrapper(
        OrchestratorModule.provideScreenProjector(
            requestIdGenerator = requestIdGenerator,
            controlPointGateway = controlPointGateway,
            policy = VehicleFramePolicy(OrchestratorModule.provideTextSanitizer()),
            holder = capabilities
        )
    )
    val indicators: ActiveIndicatorsPublisher =
        OrchestratorModule.provideActiveIndicatorsPublisher(screenProjector)
    val mirror: ProjectionMirror = OrchestratorModule.provideProjectionMirror()

    /** The rider's preferences, so a test can turn call interception off. */
    val settings = FakeSettingsRepository()

    /**
     * The three buses of the session, one owner each, built by [NotificationModule] as in the real
     * graph. A test drives the platform edge through these, or through [callDriver] for calls.
     */
    val callBus = NotificationModule.provideCallNotificationBus()
    val messageBus = NotificationModule.provideMessageNotificationBus()
    val navigationBus = NotificationModule.provideNavigationNotificationBus()

    /** The call bus, wrapped the way `OpenSabNotificationListenerService` would drive it. */
    val callDriver = CallNotificationDriver(callBus)

    /**
     * The real message source, so the harness can drive it the way the phone does: over
     * [messageBus], through the filter and the discard policy.
     */
    val message: MessageSource = MessageModule.provideMessageSource(messageBus)
    val music: MusicManager =
        OrchestratorModule.provideMusicManager(media)
    val nav: NavigationManager = OrchestratorModule.provideNavManager(navigationBus)
    val calls: CallManager =
        OrchestratorModule.provideDefaultCallManager(NoAudioFocus, callBus)

    /**
     * Built by the same factory as the app's graph, so the `indicators` participant lights exactly
     * the bits a phone lights.
     */
    val indicatorTranslators: List<IndicatorTranslator> = OrchestratorModule.provideIndicatorTranslators(
        musicManager = music,
        navManager = nav,
        callManager = calls,
        messageSource = message,
        activeIndicators = indicators
    )

    /**
     * Exposed so a test can put words in the rider's mouth and read back the LISTEN, which is what
     * `MessageReplyE2eTest` drives.
     */
    val recognizer = MockSpeechRecognizer()
    val cuePlayer = MockCuePlayer()

    /** Plugged in: LINK_UP sounds as soon as the link is up, as it did before it waited. */
    val headset = FakeHeadsetPresence(connected = true)
    val quickReplies = MutableStateFlow(listOf("On my way", "Can't talk now"))
    val sentLinger = MutableStateFlow(2.seconds)
    val contactDirectory = MockContactDirectory()
    val callDialer = MockCallDialer()
    val callMenuAccess = MockCallMenuAccess()
    val smsSender = MockSmsSender()
    val messageMenuAccess = MockMessageMenuAccess()
    val navLauncher: NavigationLauncher = MockNavigationLauncher()

    /**
     * Built once and handed to both [engine] and the session announcer, as the one `@Singleton`
     * `AudioModule.provideCuePlayer` is in production. Two `enginePlayer(cuePlayer)` calls would
     * give each its own decorator and let `LINK_UP`/`LINK_DOWN` pass through a gate no test ever wraps.
     */
    val wrappedCuePlayer: CuePlayer = enginePlayer(cuePlayer)

    val pageFactory: PageProviderFactory = DefaultPageProviderFactory(
        musicManager = music,
        navManager = nav,
        callManager = calls,
        weatherSource = weather,
        messageSource = message,
        telecomAdapter = telecom,
        volumeSource = volume,
        speechRecognizer = recognizer,
        contactDirectory = contactDirectory,
        callDialer = callDialer,
        callMenuAccess = callMenuAccess,
        smsSender = smsSender,
        messageMenuAccess = messageMenuAccess,
        navigationLauncher = navLauncher,
        deviceUnlockSource = FakeDeviceUnlockSource(),
        quickReplies = quickReplies,
        sentLinger = sentLinger,
        rootMenuEntries = OrchestratorModule.provideRootMenuEntries(settings, sessionScope),
        handlebarVolumeControl = volumeControlOn,
        inboundCallMode = settings.settingsFlow.map { it.inboundCallMode }.distinctUntilChanged(),
        inboundMessageMode = settings.settingsFlow.map { it.inboundMessageMode }.distinctUntilChanged(),
        presentation = ClusterPresentation(clusterResolver, captionResolver)
    )

    val engine: BaseStateManager = OrchestratorModule.provideBaseStateManager(
        screenProjector = screenProjector,
        requestIdGenerator = requestIdGenerator,
        capabilities = capabilities,
        activeIndicators = indicators,
        projectionMirror = mirror,
        pageVoice = PageVoice(speechResolver, spoken, sessionScope = sessionScope),
        cuePlayer = wrappedCuePlayer,
        pageFactory = pageFactory
    )
    val liveSessionLinkHolder = LiveSessionLinkHolder()
    val sabModeWatchdog = SabModeWatchdog(timeout = 10.seconds)
    val sessionPipeline: SessionPipeline = DefaultSessionPipeline(
        sessionManager = OrchestratorModule.provideSecuritySessionManager(InMemorySecureKeyStore()),
        vehicleReader = DefaultVehicleInfoReader(),
        assignmentManager = DefaultAssignmentManager(),
        inputDispatcher = inputDispatcher,
        screenProjector = screenProjector,
        requestIdGenerator = requestIdGenerator,
        capabilitiesHolder = capabilities,
        controlPointGateway = controlPointGateway,
        sabModeWatchdog = sabModeWatchdog,
        sabModeRetry = SabModeRetry(),
        onSabModeNotGranted = mirror::publishGrantOverdue,
        sabModeFallbacks = SessionModule.sabModeFallbacksFrom(settings),
        activeIndicatorsPublisher = indicators,
        ctsServer = cts,
        clockSyncEnabled = { true },
        liveLinkHolder = liveSessionLinkHolder,
        announcer = CueSessionAnnouncer(wrappedCuePlayer, headset, settle = linkSettle),
        storedProfileResolver = vehicles.storedProfileResolver()
    )
    val handoverCoordinator: ClusterHandoverCoordinator = DefaultClusterHandoverCoordinator(
        liveLinkHolder = liveSessionLinkHolder,
        inputDispatcher = inputDispatcher,
        requestIdGenerator = requestIdGenerator,
        sabModeWatchdog = sabModeWatchdog,
        projectionMirror = mirror
    )

    /** Wired by the real app's factory, so [connect] starts exactly what a phone starts. */
    val sessionParticipants: List<SessionParticipant> = OrchestratorModule.provideSessionParticipants(
        baseStateManager = engine,
        inputDispatcher = inputDispatcher,
        musicManager = music,
        navManager = nav,
        callManager = calls,
        weatherSource = weather,
        messageSource = message,
        indicators = indicatorTranslators,
        vehicleRepository = vehicles,
        settingsRepository = FakeSettingsRepository(),
        favoriteMediaRepository = InMemoryFavoriteMediaRepository()
    )

    /**
     * Wired like `di/SessionModule.kt` wires the app's. What the scanner and dashboard screens
     * submit through [sessionLauncher] runs here, not through [sessionPipeline] directly.
     */
    val sessionController: SabSessionController = SabSessionController(
        bonder = bonder,
        links = LinkOpener(transportFactory::connect),
        pipeline = sessionPipeline,
        participants = sessionParticipants,
        parent = sessionScope.coroutineContext,
        stallThreshold = null,
        reconnect = reconnect,
        presence = SessionModule.provideVehiclePresence(aclPresence)
    )

    val sessions: SabSessions = sessionController

    val sessionLauncher: SessionLauncher = FakeSessionLauncher(sessionController)

    // --- The screens ---------------------------------------------------------------------------

    val viewModelStore = ViewModelStore()

    fun scanViewModel(): VehicleScanViewModel = VehicleScanViewModel(
        bleScanner = scanner,
        vehicleRepository = vehicles,
        sabSessions = sessions,
        sessionLauncher = sessionLauncher,
        // The harness scans, so the permission is there. Denying it here would make every
        // end-to-end test fail on the guard instead of on what it is actually about.
        permissionEntries = PermissionCatalog.entriesFor(sdkInt = 36),
        permissionStatusReader = FakePermissionStatusReader(fallback = PermissionStatus.GRANTED)
    ).also { viewModelStore.put(SCAN_KEY, it) }

    fun dashboardViewModel(): DashboardViewModel = DashboardViewModel(
        musicManager = music,
        handoverCoordinator = handoverCoordinator,
        sabSessions = sessions,
        sessionLauncher = sessionLauncher,
        projectionMirror = mirror,
        inputDispatcher = inputDispatcher,
        permissionEntries = PermissionCatalog.entriesFor(sdkInt = 36),
        permissionStatusReader = FakePermissionStatusReader(fallback = PermissionStatus.GRANTED)
    ).also { viewModelStore.put(DASHBOARD_KEY, it) }

    // --- Driving the session -------------------------------------------------------------------

    /**
     * Boots the BTU and opens a session the only way the app does: a START through [sessionLauncher]
     * into [sessionController], settled once the controller publishes `Connected` or `Error`.
     *
     * Never a direct call into the pipeline: connecting through a path production does not take once
     * let the dashboard's handover button break on every real session with the whole suite green.
     */
    suspend fun connect(
        mac: String = MAC,
        origin: SessionOrigin = SessionOrigin.RECEIVER
    ): Result<SabSessionState.Connected> {
        btu.boot()
        val before = sessions.state.value
        sessionLauncher.start(mac, origin)
        return when (val settled = sessions.state.first {
            it !== before && (it is SabSessionState.Connected || it is SabSessionState.Error)
        }) {
            is SabSessionState.Connected -> Result.success(settled)
            is SabSessionState.Error -> Result.failure(settled.cause)
            else -> error("Unreachable: settled on $settled")
        }
    }

    /** Asks [sessionController] to close the live session and waits until it is `Disconnected`. */
    suspend fun disconnect() {
        sessionLauncher.stop(SessionOrigin.DASHBOARD)
        sessions.state.first { it is SabSessionState.Disconnected }
    }

    /**
     * The cluster hands the screen over, and the carousel goes out.
     *
     * A 0x09 FunctionNotification and nothing else: the Receipt of the 0x0A acknowledges the
     * command, never the handover.
     */
    suspend fun handOverScreen() {
        btu.grantSabMode()
    }

    /** Hands back a transport that is already the simulated BTU, whatever address is asked for. */
    class FixedTransportFactory(private val transport: GattTransport) : GattTransportFactory {
        val connectedAddresses = mutableListOf<String>()

        /** Failures to answer with before the transport opens, first in first out. */
        val failures = ArrayDeque<Throwable>()

        override suspend fun connect(macAddress: String): Result<GattTransport> {
            connectedAddresses += macAddress
            val failure = failures.removeFirstOrNull()
            return if (failure != null) Result.failure(failure) else Result.success(transport)
        }
    }

    /**
     * Records every save: a vehicle saved twice for one pairing means two owners of the save, and it
     * is invisible to a store that only keeps the last write.
     */
    class CountingVehicleRepository(
        private val delegate: InMemoryVehicleRepository = InMemoryVehicleRepository()
    ) : VehicleRepository by delegate {
        private val _saved = mutableListOf<String>()

        /** The MAC address of every save, in order. */
        val saved: List<String> get() = synchronized(_saved) { _saved.toList() }

        override suspend fun saveVehicle(vehicle: KnownVehicle) {
            synchronized(_saved) { _saved += vehicle.macAddress }
            delegate.saveVehicle(vehicle)
        }
    }

    /** Stands in for the pairing seam, which has no JVM-testable half. */
    class RecordingDeviceBonder(
        private val result: Result<Unit> = Result.success(Unit)
    ) : DeviceBonder {
        val bondedAddresses = mutableListOf<String>()

        override suspend fun ensureBonded(macAddress: String): Result<Unit> {
            bondedAddresses += macAddress
            return result
        }
    }

    /**
     * [SessionLauncher] over a real [SabSessionController], the way `AndroidSessionLauncher` sits
     * over the one Hilt builds — `submit` is the only thing either does, with an ever-growing
     * `startId` standing in for the one Android itself assigns each `onStartCommand`.
     */
    class FakeSessionLauncher(private val controller: SabSessionController) : SessionLauncher {
        private val nextStartId = AtomicInteger(1)

        override fun start(macAddress: String, origin: SessionOrigin) {
            controller.submit(SessionCommand.Start(macAddress, origin, nextStartId.getAndIncrement()))
        }

        override fun stop(origin: SessionOrigin) {
            controller.submit(SessionCommand.Disconnect(origin, nextStartId.getAndIncrement()))
        }
    }

    companion object {
        const val MAC = "02:00:00:00:00:AB"
        private const val SCAN_KEY = "scan"
        private const val DASHBOARD_KEY = "dashboard"
    }
}

/** Whether a projected payload carries [text] as ASCII. */
internal fun ByteArray.containsAscii(text: String): Boolean {
    val needle = text.toByteArray(Charsets.US_ASCII)
    if (needle.isEmpty() || needle.size > size) return false
    return (0..size - needle.size).any { start ->
        needle.indices.all { this[start + it] == needle[it] }
    }
}

/** [containsAscii] in UTF-8, for localized strings. */
internal fun ByteArray.containsUtf8(text: String): Boolean {
    val needle = text.toByteArray(Charsets.UTF_8)
    if (needle.isEmpty() || needle.size > size) return false
    return (0..size - needle.size).any { start ->
        needle.indices.all { this[start + it] == needle[it] }
    }
}
