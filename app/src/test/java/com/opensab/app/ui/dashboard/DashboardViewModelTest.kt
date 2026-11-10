package com.opensab.app.ui.dashboard

import app.cash.turbine.test
import com.opensab.app.service.SessionLauncher
import com.opensab.app.ui.MainDispatcherRule
import com.opensab.app.ui.permissions.FakePermissionStatusReader
import com.opensab.app.ui.permissions.PermissionCatalog
import com.opensab.app.ui.permissions.PermissionEntry
import com.opensab.app.ui.permissions.PermissionId
import com.opensab.app.ui.permissions.PermissionStatus
import com.opensab.app.ui.permissions.PermissionStatusReader
import com.opensab.cluster.ClusterHandoverCoordinator
import com.opensab.cluster.mirror.MirrorState
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.domain.music.MusicManager
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.TrackMetadata
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.input.HandlebarEvent
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.SabSessions
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.crypto.SecuritySession
import com.opensab.sab.session.input.InputDispatcher
import com.opensab.sab.session.input.ScreenGrant
import com.opensab.sab.session.vehicle.KnownVehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val mac = "AA:BB:CC:11:22:33"

    @Test
    fun initialStateIsDisconnectedWithNoVehicle() = runTest {
        val viewModel = createViewModel()

        assertEquals(SabSessionState.Disconnected, viewModel.uiState.value.sessionState)
        assertNull(viewModel.uiState.value.activeVehicle)
        assertEquals("", viewModel.uiState.value.musicState.metadata.title)
    }

    @Test
    fun reflectsTheOrchestratorSessionAndItsVehicle() = runTest {
        val sessions = FakeSabSessions()
        val viewModel = createViewModel(sabSessions = sessions)

        sessions.emitConnected(mac)

        assertTrue(viewModel.uiState.value.sessionState is SabSessionState.Connected)
        assertEquals(mac, viewModel.uiState.value.activeVehicle?.macAddress)
    }

    @Test
    fun keepsTheLastVehicleOnScreenWhenTheLinkDrops() = runTest {
        val sessions = FakeSabSessions()
        val viewModel = createViewModel(sabSessions = sessions)

        sessions.emitConnected(mac)
        sessions.emit(SabSessionState.Disconnected)

        assertEquals(SabSessionState.Disconnected, viewModel.uiState.value.sessionState)
        assertEquals(
            "Blanking out the motorcycle identity mid-ride reads as a crash",
            mac,
            viewModel.uiState.value.activeVehicle?.macAddress
        )
    }

    @Test
    fun `disconnect asks the service`() = runTest {
        val sessions = FakeSabSessions()
        val launcher = RecordingSessionLauncher()
        val viewModel = createViewModel(sabSessions = sessions, launcher = launcher)
        sessions.emitConnected(mac)

        viewModel.uiEvents.test {
            viewModel.sendIntent(DashboardUiIntent.Disconnect)
            // The fake launcher only records the ask; a real one's SabSessionController is what
            // actually closes the session and moves the state — reproduced here by hand, since
            // NavigateToScan reacts to that state, not to the intent itself.
            sessions.emit(SabSessionState.Disconnected)

            assertTrue(awaitItem() is DashboardUiEvent.NavigateToScan)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(SessionOrigin.DASHBOARD), launcher.stops)
        assertEquals(SabSessionState.Disconnected, viewModel.uiState.value.sessionState)
    }

    @Test
    fun emitsNavigateToScanWhenTheLinkDiesOnItsOwn() = runTest {
        val sessions = FakeSabSessions()
        val launcher = RecordingSessionLauncher()
        val viewModel = createViewModel(sabSessions = sessions, launcher = launcher)
        sessions.emitConnected(mac)

        viewModel.uiEvents.test {
            sessions.emit(SabSessionState.Disconnected)

            assertTrue(awaitItem() is DashboardUiEvent.NavigateToScan)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("Nothing asked the service to disconnect", emptyList<SessionOrigin>(), launcher.stops)
    }

    @Test
    fun musicStateUpdatesAreReflectedInUiState() = runTest {
        val musicManager = FakeMusicManager()
        val viewModel = createViewModel(musicManager = musicManager)

        musicManager.emitMusic(
            MusicState(
                status = PlaybackStatus.PLAYING,
                metadata = TrackMetadata("Yesterday", "The Beatles")
            )
        )

        val music = viewModel.uiState.value.musicState
        assertEquals("Yesterday", music.metadata.title)
        assertEquals("The Beatles", music.metadata.artist)
        assertEquals(PlaybackStatus.PLAYING, music.status)
    }

    @Test
    fun openSettingsIntentEmitsNavigateToSettings() = runTest {
        val viewModel = createViewModel()

        viewModel.uiEvents.test {
            viewModel.sendIntent(DashboardUiIntent.OpenSettings)
            assertTrue(awaitItem() is DashboardUiEvent.NavigateToSettings)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------------------------
    // Mirror
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the mirrored state reaches the screen`() = runTest {
        val mirror = ProjectionMirror()
        val viewModel = createViewModel(projectionMirror = mirror)

        assertEquals(MirrorState.Idle, viewModel.uiState.value.mirrorState)

        mirror.publishClusterOwnsScreen()

        assertEquals(
            MirrorState.ClusterOwnsScreen(handoverPending = false),
            viewModel.uiState.value.mirrorState
        )
    }

    @Test
    fun `a tap on a zone injects that very press`() = runTest {
        val dispatcher = RecordingMirrorInputDispatcher()
        val viewModel = createViewModel(inputDispatcher = dispatcher)

        viewModel.sendIntent(DashboardUiIntent.MirrorAction(FunctionId.SELECT))
        runCurrent()

        assertEquals(listOf(FunctionId.SELECT), dispatcher.injected)
    }

    /**
     * The UI already disables the zones outside SAB_MODE, so a refusal here means the two states
     * crossed. It is logged and dropped: throwing would take the dashboard down over a press that
     * simply arrived too late.
     */
    @Test
    fun `a refused press leaves the screen standing`() = runTest {
        val dispatcher = RecordingMirrorInputDispatcher().apply { refuse = true }
        val viewModel = createViewModel(inputDispatcher = dispatcher)

        viewModel.sendIntent(DashboardUiIntent.MirrorAction(FunctionId.SELECT))
        runCurrent()

        assertEquals(listOf(FunctionId.SELECT), dispatcher.injected)
        assertEquals(SabSessionState.Disconnected, viewModel.uiState.value.sessionState)
    }

    @Test
    fun `the handover button asks the coordinator for the screen`() = runTest {
        val coordinator = FakeClusterHandoverCoordinator()
        val viewModel = createViewModel(handoverCoordinator = coordinator)

        viewModel.sendIntent(DashboardUiIntent.RequestHandover)
        runCurrent()

        assertEquals(1, coordinator.handoverRequests)
    }

    // region The media access card

    /**
     * The card **appears** when the grant is missing, so the state is read before anything is
     * collected: a default that guessed "missing" would flash the card on a phone that has it.
     */
    @Test
    fun `should know whether the media access is there before anything is collected`() = runTest {
        val missing = createViewModel(
            permissionStatusReader = deniedNotificationAccess()
        )
        val present = createViewModel()

        assertFalse(missing.uiState.value.mediaAccessGranted)
        assertTrue(present.uiState.value.mediaAccessGranted)
    }

    @Test
    fun `should re-read the media access when the screen comes back`() = runTest {
        val reader = FakePermissionStatusReader(
            statuses = mapOf(PermissionId.NOTIFICATION_ACCESS to PermissionStatus.DENIED),
            fallback = PermissionStatus.GRANTED
        )
        val viewModel = createViewModel(permissionStatusReader = reader)
        assertFalse(viewModel.uiState.value.mediaAccessGranted)

        reader.statuses = mapOf(PermissionId.NOTIFICATION_ACCESS to PermissionStatus.GRANTED)
        viewModel.sendIntent(DashboardUiIntent.RefreshPermissions)

        assertTrue(viewModel.uiState.value.mediaAccessGranted)
    }

    /** The card explains; the permissions screen asks. */
    @Test
    fun `should ask to open the permissions screen instead of the system one`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiEvents.test {
            viewModel.sendIntent(DashboardUiIntent.OpenPermissions)

            assertEquals(DashboardUiEvent.NavigateToPermissions, awaitItem())
        }
    }

    private fun deniedNotificationAccess() = FakePermissionStatusReader(
        statuses = mapOf(PermissionId.NOTIFICATION_ACCESS to PermissionStatus.DENIED),
        fallback = PermissionStatus.GRANTED
    )

    // endregion

    /** The music stream runs under `whileSubscribed()`: it starts once `uiState` is observed. */
    private fun TestScope.createViewModel(
        musicManager: MusicManager = FakeMusicManager(),
        handoverCoordinator: ClusterHandoverCoordinator = FakeClusterHandoverCoordinator(),
        sabSessions: SabSessions = FakeSabSessions(),
        launcher: SessionLauncher = RecordingSessionLauncher(),
        projectionMirror: ProjectionMirror = ProjectionMirror(),
        inputDispatcher: InputDispatcher = RecordingMirrorInputDispatcher(),
        permissionEntries: List<PermissionEntry> = PermissionCatalog.entriesFor(sdkInt = 36),
        permissionStatusReader: PermissionStatusReader = FakePermissionStatusReader(
            fallback = PermissionStatus.GRANTED
        )
    ) = mainDispatcherRule.register(
        DashboardViewModel(
            musicManager = musicManager,
            handoverCoordinator = handoverCoordinator,
            sabSessions = sabSessions,
            sessionLauncher = launcher,
            projectionMirror = projectionMirror,
            inputDispatcher = inputDispatcher,
            permissionEntries = permissionEntries,
            permissionStatusReader = permissionStatusReader
        )
    ).also { viewModel ->
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect {} }
    }

    /** Records what the screen asks of the service, instead of driving a real controller. */
    private class RecordingSessionLauncher : SessionLauncher {
        val starts = mutableListOf<Pair<String, SessionOrigin>>()
        val stops = mutableListOf<SessionOrigin>()

        override fun start(macAddress: String, origin: SessionOrigin) {
            starts += macAddress to origin
        }

        override fun stop(origin: SessionOrigin) {
            stops += origin
        }
    }

    /** The state [SabSessionController] would own, driven by hand instead of by a real pipeline. */
    private class FakeSabSessions : SabSessions {
        private val _state = MutableStateFlow<SabSessionState>(SabSessionState.Disconnected)
        override val state: StateFlow<SabSessionState> = _state.asStateFlow()

        fun emit(state: SabSessionState) {
            _state.value = state
        }

        fun emitConnected(macAddress: String) = emit(
            SabSessionState.Connected(
                vehicle = KnownVehicle(
                    macAddress = macAddress,
                    displayName = "Honda Forza 750",
                    vehicleInfo = VehicleInformation(
                        vehicleType = 0x01,
                        displayCapability = byteArrayOf(0x01, 0x02),
                        switchType = SwitchType.TYPE_B,
                        categoryId = 0x01,
                        seriesCode = "RH10",
                        destinationId = DestinationId.EU
                    )
                ),
                session = SecuritySession(
                    macAddress = macAddress,
                    aesKey = ByteArray(16),
                    isValidated = true
                )
            )
        )
    }

    /** Records what the mirror taps ask of the dispatcher, and can refuse like HU_MODE does. */
    private class RecordingMirrorInputDispatcher : InputDispatcher {
        override val events: SharedFlow<HandlebarEvent> = MutableSharedFlow()
        override val currentMode: StateFlow<DashboardMode> =
            MutableStateFlow(DashboardMode.SAB_MODE)
        override val screenGrant: StateFlow<ScreenGrant> = MutableStateFlow(ScreenGrant.CONFIRMED)

        override fun assumeSabMode(): Boolean = false

        val injected = mutableListOf<FunctionId>()
        var refuse = false

        override suspend fun startListening(transport: GattTransport, scope: CoroutineScope) = Unit
        override fun stopListening() = Unit
        override suspend fun requestSabMode(transport: GattTransport, requestId: Int): Result<Unit> =
            Result.success(Unit)

        override suspend fun injectMirrorPress(functionId: FunctionId): Result<Unit> {
            injected += functionId
            return if (refuse) {
                Result.failure(IllegalStateException("The cluster has not granted SAB_MODE"))
            } else {
                Result.success(Unit)
            }
        }
    }

    private class FakeMusicManager : MusicManager {
        private val _musicState = MutableStateFlow(MusicState())
        override val currentState: StateFlow<MusicState> = _musicState.asStateFlow()

        fun emitMusic(state: MusicState) {
            _musicState.value = state
        }

        override fun start(scope: CoroutineScope) = Unit
        override fun stop() = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun togglePlayPause() = Unit
        override fun skipToNext() = Unit
        override fun skipToPrevious() = Unit
        override fun volumeUp() = Unit
        override fun volumeDown() = Unit
        override fun toggleMute() = Unit
        override fun reset() = Unit
    }

    private class FakeClusterHandoverCoordinator : ClusterHandoverCoordinator {
        var handoverRequests = 0
            private set
        var handoverFailure: Throwable? = null

        override suspend fun requestClusterHandover(): Result<Unit> {
            handoverRequests++
            return handoverFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }
    }
}
