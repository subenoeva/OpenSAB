package com.opensab.app.ui.scan

import app.cash.turbine.test
import com.opensab.app.service.SessionLauncher
import com.opensab.app.ui.MainDispatcherRule
import com.opensab.app.ui.permissions.FakePermissionStatusReader
import com.opensab.app.ui.permissions.PermissionCatalog
import com.opensab.app.ui.permissions.PermissionEntry
import com.opensab.app.ui.permissions.PermissionId
import com.opensab.app.ui.permissions.PermissionStatus
import com.opensab.app.ui.permissions.PermissionStatusReader
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.SabSessions
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.TransportUnreachableException
import com.opensab.sab.session.ble.BondFailedException
import com.opensab.sab.session.ble.BondTimedOutException
import com.opensab.sab.session.ble.MockBleScanner
import com.opensab.sab.session.ble.ScannedDevice
import com.opensab.sab.session.crypto.SecuritySession
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.InMemoryVehicleRepository
import com.opensab.sab.session.vehicle.KnownVehicle
import com.opensab.sab.session.vehicle.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleScanViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val mac = "AA:BB:CC:11:22:33"
    private val newMac = "AA:BB:CC:11:22:99"

    @Test
    fun initialStateHasNoDevicesAndIsNotScanning() = runTest {
        val viewModel = createViewModel()

        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals(0, viewModel.uiState.value.scannedDevices.size)
        assertNull(viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.pairingAddress)
    }

    @Test
    fun scanningDiscoversDevicesAndUpdatesUiStateViaIntent() = runTest {
        val scanner = MockBleScanner()
        val viewModel = createViewModel(scanner = scanner)

        viewModel.sendIntent(VehicleScanUiIntent.StartScan)
        assertTrue(viewModel.uiState.value.isScanning)

        scanner.emitDiscoveredDevice(
            ScannedDevice(
                name = "HONDA-BTU-RH10",
                address = mac,
                rssi = -65,
                isSabCompatible = true
            )
        )

        assertEquals(1, viewModel.uiState.value.scannedDevices.size)
        assertEquals("HONDA-BTU-RH10", viewModel.uiState.value.scannedDevices.first().name)

        viewModel.sendIntent(VehicleScanUiIntent.StopScan)
        assertFalse(viewModel.uiState.value.isScanning)
    }

    /**
     * This screen never bonds, opens a transport or drives the pipeline itself: it asks the
     * service, through [SessionLauncher], and nothing else.
     */
    @Test
    fun `connecting from the scanner asks the service and never opens the link itself`() = runTest {
        val launcher = RecordingSessionLauncher()
        val vehicleRepo = InMemoryVehicleRepository().apply { saveVehicle(knownVehicle(mac)) }
        val viewModel = createViewModel(vehicleRepository = vehicleRepo, launcher = launcher)

        viewModel.sendIntent(
            VehicleScanUiIntent.ConnectToDevice(ScannedDevice("HONDA-BTU-RH10", mac, -60))
        )

        assertEquals(listOf(mac to SessionOrigin.SCANNER), launcher.starts)
        assertEquals(mac, viewModel.uiState.value.pairingAddress)
    }

    @Test
    fun `cancel connect asks the service to disconnect`() = runTest {
        val launcher = RecordingSessionLauncher()
        val viewModel = createViewModel(launcher = launcher)

        viewModel.sendIntent(
            VehicleScanUiIntent.ConnectToDevice(ScannedDevice("HONDA-BTU-RH10", mac, -60))
        )
        viewModel.sendIntent(VehicleScanUiIntent.CancelConnect)

        assertEquals(listOf(SessionOrigin.SCANNER), launcher.stops)
        assertNull(
            "A cancelled attempt must stop reporting progress on the row",
            viewModel.uiState.value.pairingAddress
        )
    }

    // region Cluster question on a new motorcycle

    @Test
    fun `connecting to a new motorcycle asks for the cluster first`() = runTest {
        val launcher = RecordingSessionLauncher()
        val viewModel = createViewModel(launcher = launcher)
        val device = ScannedDevice("HONDA-BTU-NEW", newMac, -60)

        viewModel.sendIntent(VehicleScanUiIntent.ConnectToDevice(device))

        assertEquals(device, viewModel.uiState.value.askClusterFor)
        assertTrue(launcher.starts.isEmpty())
    }

    @Test
    fun `answering stores the profile before starting the session`() = runTest {
        val vehicleRepo = InMemoryVehicleRepository()
        // Proves the order, not just the end state: if `connectToDevice` ran before the profile was
        // saved, this would still see null the moment `start` fires.
        var clusterDisplayAtStart: ClusterDisplay? = null
        val launcher = RecordingSessionLauncher(
            onStart = { clusterDisplayAtStart = runBlocking { vehicleRepo.getProfile(newMac) }?.clusterDisplay }
        )
        val viewModel = createViewModel(vehicleRepository = vehicleRepo, launcher = launcher)
        val device = ScannedDevice("HONDA-BTU-NEW", newMac, -60)
        viewModel.sendIntent(VehicleScanUiIntent.ConnectToDevice(device))

        viewModel.sendIntent(VehicleScanUiIntent.AnswerClusterDisplay(ClusterDisplay.SEGMENT))

        assertEquals(
            "The profile must already be written when the session is asked to start",
            ClusterDisplay.SEGMENT,
            clusterDisplayAtStart
        )
        assertEquals(ClusterDisplay.SEGMENT, vehicleRepo.getProfile(newMac)!!.clusterDisplay)
        assertEquals(listOf(newMac to SessionOrigin.SCANNER), launcher.starts)
        assertNull(viewModel.uiState.value.askClusterFor)
    }

    @Test
    fun `a known motorcycle connects without asking`() = runTest {
        val vehicleRepo = InMemoryVehicleRepository().apply { saveVehicle(knownVehicle(mac)) }
        val launcher = RecordingSessionLauncher()
        val viewModel = createViewModel(vehicleRepository = vehicleRepo, launcher = launcher)

        viewModel.sendIntent(
            VehicleScanUiIntent.ConnectToDevice(ScannedDevice("HONDA-BTU-RH10", mac, -60))
        )

        assertNull(viewModel.uiState.value.askClusterFor)
        assertEquals(1, launcher.starts.size)
    }

    /**
     * `vehiclesFlow` is DataStore-backed in production and can still be catching up when
     * `ConnectToDevice` arrives. Without falling back to the repository, a motorcycle on file would
     * be re-asked, and the answer would overwrite its stored profile (`bodyStyle` reset to GENERIC).
     */
    @Test
    fun `a known motorcycle connects without asking even if the vehicles flow has not caught up`() = runTest {
        val vehicleRepo = StaleVehiclesFlowRepository(
            InMemoryVehicleRepository().apply { saveVehicle(knownVehicle(mac)) }
        )
        val launcher = RecordingSessionLauncher()
        val viewModel = createViewModel(vehicleRepository = vehicleRepo, launcher = launcher)

        viewModel.sendIntent(
            VehicleScanUiIntent.ConnectToDevice(ScannedDevice("HONDA-BTU-RH10", mac, -60))
        )

        assertNull(
            "knownVehicles is empty because vehiclesFlow never emits, but getVehicle still knows it",
            viewModel.uiState.value.askClusterFor
        )
        assertEquals(1, launcher.starts.size)
    }

    @Test
    fun `dismissing the question does not connect`() = runTest {
        val launcher = RecordingSessionLauncher()
        val viewModel = createViewModel(launcher = launcher)
        val device = ScannedDevice("HONDA-BTU-NEW", newMac, -60)
        viewModel.sendIntent(VehicleScanUiIntent.ConnectToDevice(device))

        viewModel.sendIntent(VehicleScanUiIntent.DismissClusterQuestion)

        assertNull(viewModel.uiState.value.askClusterFor)
        assertTrue(launcher.starts.isEmpty())
    }

    // endregion

    @Test
    fun `a bond failure of its own request shows BondFailed`() = runTest {
        val sessions = FakeSabSessions()
        val vehicleRepo = InMemoryVehicleRepository().apply { saveVehicle(knownVehicle(mac)) }
        val viewModel = createViewModel(vehicleRepository = vehicleRepo, sabSessions = sessions)

        viewModel.uiEvents.test {
            viewModel.sendIntent(
                VehicleScanUiIntent.ConnectToDevice(ScannedDevice("HONDA-BTU-RH10", mac, -60))
            )
            sessions.emit(SabSessionState.Error(BondFailedException(mac, reason = 1)))

            val event = awaitItem()
            assertTrue("Expected the rider to be told, got $event", event is VehicleScanUiEvent.ShowError)
            val error = (event as VehicleScanUiEvent.ShowError).error
            assertTrue("A failed bond is a BondFailed, got $error", error is ScanError.BondFailed)
            assertTrue(
                "The decoded UNBOND_REASON is what tells the rider to forget the motorcycle",
                (error as ScanError.BondFailed).detail.orEmpty().contains("UNBOND_REASON_AUTH_FAILED")
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(viewModel.uiState.value.pairingAddress)
    }

    @Test
    fun `a bond that times out shows BondTimedOut and not a raw pipeline failure`() = runTest {
        val sessions = FakeSabSessions()
        val vehicleRepo = InMemoryVehicleRepository().apply { saveVehicle(knownVehicle(mac)) }
        val viewModel = createViewModel(vehicleRepository = vehicleRepo, sabSessions = sessions)

        viewModel.sendIntent(
            VehicleScanUiIntent.ConnectToDevice(ScannedDevice("HONDA-BTU-RH10", mac, -60))
        )
        sessions.emit(SabSessionState.Error(BondTimedOutException(mac, timeout = 30.seconds)))

        val error = viewModel.uiState.value.error
        assertTrue("A bond timeout is a BondTimedOut, got $error", error is ScanError.BondTimedOut)
    }

    @Test
    fun `an unreachable link of its own request shows TransportUnreachable`() = runTest {
        val sessions = FakeSabSessions()
        val vehicleRepo = InMemoryVehicleRepository().apply { saveVehicle(knownVehicle(mac)) }
        val viewModel = createViewModel(vehicleRepository = vehicleRepo, sabSessions = sessions)

        viewModel.uiEvents.test {
            viewModel.sendIntent(
                VehicleScanUiIntent.ConnectToDevice(ScannedDevice("HONDA-BTU-RH10", mac, -60))
            )
            sessions.emit(SabSessionState.Error(TransportUnreachableException(mac, cause = null)))

            assertEquals(
                VehicleScanUiEvent.ShowError(ScanError.TransportUnreachable),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(ScanError.TransportUnreachable, viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.pairingAddress)
    }

    /**
     * The receiver's own attempt can fail while this screen has asked for nothing: that error is
     * not this screen's business, so it stays quiet. `pairingAddress` is what says who asked.
     */
    @Test
    fun `an error it did not ask for is not shown`() = runTest {
        val sessions = FakeSabSessions()
        val viewModel = createViewModel(sabSessions = sessions)

        viewModel.uiEvents.test {
            sessions.emit(SabSessionState.Error(IllegalStateException("someone else's attempt")))
            expectNoEvents()
        }
        assertNull(viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.pairingAddress)
    }

    @Test
    fun `the row stops spinning when the session connects or fails`() = runTest {
        val connectSessions = FakeSabSessions()
        val connectVehicleRepo = InMemoryVehicleRepository().apply { saveVehicle(knownVehicle(mac)) }
        val connectViewModel = createViewModel(vehicleRepository = connectVehicleRepo, sabSessions = connectSessions)
        connectViewModel.sendIntent(
            VehicleScanUiIntent.ConnectToDevice(ScannedDevice("HONDA-BTU-RH10", mac, -60))
        )
        assertEquals(mac, connectViewModel.uiState.value.pairingAddress)
        connectSessions.emitConnected(mac)
        assertNull(connectViewModel.uiState.value.pairingAddress)

        val failSessions = FakeSabSessions()
        val failVehicleRepo = InMemoryVehicleRepository().apply { saveVehicle(knownVehicle(mac)) }
        val failViewModel = createViewModel(vehicleRepository = failVehicleRepo, sabSessions = failSessions)
        failViewModel.sendIntent(
            VehicleScanUiIntent.ConnectToDevice(ScannedDevice("HONDA-BTU-RH10", mac, -60))
        )
        assertEquals(mac, failViewModel.uiState.value.pairingAddress)
        failSessions.emit(SabSessionState.Error(BondFailedException(mac, reason = 1)))
        assertNull(failViewModel.uiState.value.pairingAddress)
    }

    /**
     * Saving the paired motorcycle is owned by `KnownVehicleParticipant` alone. That it is saved
     * exactly once is proven end to end in `ControllerSessionE2eTest`.
     */
    @Test
    fun `the scanner leaves saving the paired vehicle to the session`() = runTest {
        val vehicleRepo = InMemoryVehicleRepository()
        val sessions = FakeSabSessions()
        val viewModel = createViewModel(vehicleRepository = vehicleRepo, sabSessions = sessions)

        viewModel.uiEvents.test {
            viewModel.sendIntent(
                VehicleScanUiIntent.ConnectToDevice(ScannedDevice("HONDA-BTU-RH10", mac, -60))
            )
            sessions.emitConnected(mac)

            assertTrue(awaitItem() is VehicleScanUiEvent.NavigateToDashboard)
            cancelAndIgnoreRemainingEvents()
        }

        assertNull("The screen must not save the vehicle itself", vehicleRepo.getVehicle(mac))
        assertNull(viewModel.uiState.value.pairingAddress)
    }

    /**
     * Whoever opened the session — this screen, or the foreground service auto-connecting in the
     * background — the very same collector navigates. Only one observer, so it can never fire twice
     * for the same connection.
     */
    @Test
    fun emitsNavigateToDashboardWhenSomethingElseOpensTheSession() = runTest {
        val sessions = FakeSabSessions()
        val viewModel = createViewModel(sabSessions = sessions)

        viewModel.uiEvents.test {
            sessions.emitConnected(mac)

            assertTrue(awaitItem() is VehicleScanUiEvent.NavigateToDashboard)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun knownVehiclesFollowTheRepositoryWithoutAnyManualRefresh() = runTest {
        val vehicleRepo = InMemoryVehicleRepository()
        val viewModel = createViewModel(vehicleRepository = vehicleRepo)

        assertTrue(viewModel.uiState.value.knownVehicles.isEmpty())

        vehicleRepo.saveVehicle(knownVehicle(mac))
        assertEquals(listOf(mac), viewModel.uiState.value.knownVehicles.map { it.macAddress })

        vehicleRepo.removeVehicle(mac)
        assertTrue(
            "The screen reads vehiclesFlow, so a removal reaches it with no intent in between",
            viewModel.uiState.value.knownVehicles.isEmpty()
        )
    }

    @Test
    fun screenOnlyCollectorsIdleUntilSomethingObservesTheState() = runTest {
        val scanner = MockBleScanner()
        // Built without the usual subscriber: this is the app sitting in the background.
        val viewModel = mainDispatcherRule.register(
            VehicleScanViewModel(
                bleScanner = scanner,
                vehicleRepository = InMemoryVehicleRepository(),
                sabSessions = FakeSabSessions(),
                sessionLauncher = RecordingSessionLauncher(),
                permissionEntries = PermissionCatalog.entriesFor(sdkInt = 36),
                permissionStatusReader = FakePermissionStatusReader(
                    fallback = PermissionStatus.GRANTED
                )
            )
        )

        scanner.startScan()
        scanner.emitDiscoveredDevice(ScannedDevice("HONDA BTU 73FD", mac, -60))

        assertFalse(
            "Mirroring scan results into state with no screen attached is the waste whileSubscribed removes",
            viewModel.uiState.value.isScanning
        )
        assertTrue(viewModel.uiState.value.scannedDevices.isEmpty())

        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect {} }

        assertTrue("Subscribing must catch the screen up", viewModel.uiState.value.isScanning)
        assertEquals(listOf(mac), viewModel.uiState.value.scannedDevices.map { it.address })
    }

    @Test
    fun openSettingsIntentEmitsNavigateToSettings() = runTest {
        val viewModel = createViewModel()

        viewModel.uiEvents.test {
            viewModel.sendIntent(VehicleScanUiIntent.OpenSettings)
            assertTrue(awaitItem() is VehicleScanUiEvent.NavigateToSettings)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // region Scanning without the Bluetooth permission

    /** The banner already says what is missing, so no error is raised on top of it. */
    @Test
    fun `should not call the scanner without the bluetooth permission`() = runTest {
        val scanner = MockBleScanner()
        val viewModel = createViewModel(
            scanner = scanner,
            permissionStatusReader = FakePermissionStatusReader(fallback = PermissionStatus.DENIED)
        )

        viewModel.uiEvents.test {
            viewModel.sendIntent(VehicleScanUiIntent.StartScan)

            assertEquals(0, scanner.startScanCalls)
            expectNoEvents()
        }
    }

    @Test
    fun `should scan when the permission is there`() = runTest {
        val scanner = MockBleScanner()
        val viewModel = createViewModel(scanner = scanner)

        viewModel.sendIntent(VehicleScanUiIntent.StartScan)

        assertEquals(1, scanner.startScanCalls)
    }

    @Test
    fun `should know the permission before anything is collected`() = runTest {
        val denied = createViewModel(
            permissionStatusReader = FakePermissionStatusReader(fallback = PermissionStatus.DENIED)
        )
        val granted = createViewModel()

        assertFalse(denied.uiState.value.blePermissionsGranted)
        assertTrue(granted.uiState.value.blePermissionsGranted)
    }

    @Test
    fun `should re-read the permission when the screen comes back`() = runTest {
        val reader = FakePermissionStatusReader(fallback = PermissionStatus.DENIED)
        val viewModel = createViewModel(permissionStatusReader = reader)
        assertFalse(viewModel.uiState.value.blePermissionsGranted)

        reader.fallback = PermissionStatus.GRANTED
        viewModel.sendIntent(VehicleScanUiIntent.RefreshPermissions)

        assertTrue(viewModel.uiState.value.blePermissionsGranted)
    }

    /**
     * The narrow window: the permission went away **between** the check and the `startScan`.
     */
    @Test
    fun `should name a permission that vanished mid scan`() = runTest {
        val scanner = MockBleScanner().apply {
            failNextScanWith = SecurityException("Need android.permission.BLUETOOTH_SCAN")
        }
        val viewModel = createViewModel(scanner = scanner)

        viewModel.uiEvents.test {
            viewModel.sendIntent(VehicleScanUiIntent.StartScan)

            assertEquals(
                VehicleScanUiEvent.ShowError(ScanError.PermissionsMissing),
                awaitItem()
            )
        }
        assertEquals(ScanError.PermissionsMissing, viewModel.uiState.value.error)
    }

    @Test
    fun `should ask to open the permissions screen from the banner`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiEvents.test {
            viewModel.sendIntent(VehicleScanUiIntent.OpenPermissions)

            assertEquals(VehicleScanUiEvent.NavigateToPermissions, awaitItem())
        }
    }

    // endregion

    /**
     * The collectors that only paint the screen run under `whileSubscribed()`, so they stay idle
     * until something observes `uiState`. Reading `uiState.value` is not observing: the test has to
     * subscribe like the composable does.
     */
    private fun TestScope.createViewModel(
        scanner: MockBleScanner = MockBleScanner(),
        vehicleRepository: VehicleRepository = InMemoryVehicleRepository(),
        sabSessions: SabSessions = FakeSabSessions(),
        launcher: SessionLauncher = RecordingSessionLauncher(),
        permissionEntries: List<PermissionEntry> = PermissionCatalog.entriesFor(sdkInt = 36),
        permissionStatusReader: PermissionStatusReader = grantedBleReader()
    ) = mainDispatcherRule.register(
        VehicleScanViewModel(
            bleScanner = scanner,
            vehicleRepository = vehicleRepository,
            sabSessions = sabSessions,
            sessionLauncher = launcher,
            permissionEntries = permissionEntries,
            permissionStatusReader = permissionStatusReader
        )
    ).also { viewModel ->
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect {} }
    }

    /** The BLE row granted, everything else denied: the scan screen only cares about the one. */
    private fun grantedBleReader() = FakePermissionStatusReader(
        statuses = mapOf(PermissionId.BLUETOOTH to PermissionStatus.GRANTED),
        fallback = PermissionStatus.DENIED
    )

    private fun knownVehicle(macAddress: String) = KnownVehicle(
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
    )

    /**
     * [onStart] runs before the call is recorded, so a test can read state as it stood at the exact
     * moment `start` fired: the only way to prove an ordering rather than just an end state.
     */
    private class RecordingSessionLauncher(
        private val onStart: () -> Unit = {}
    ) : SessionLauncher {
        val starts = mutableListOf<Pair<String, SessionOrigin>>()
        val stops = mutableListOf<SessionOrigin>()

        override fun start(macAddress: String, origin: SessionOrigin) {
            onStart()
            starts += macAddress to origin
        }

        override fun stop(origin: SessionOrigin) {
            stops += origin
        }
    }

    /**
     * A [VehicleRepository] whose [vehiclesFlow] never catches up, but whose direct reads still
     * resolve — the shape `DataStoreVehicleRepository` takes while its initial read is still in
     * flight.
     */
    private class StaleVehiclesFlowRepository(
        private val delegate: VehicleRepository
    ) : VehicleRepository by delegate {
        override val vehiclesFlow: Flow<List<KnownVehicle>> = emptyFlow()
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
                session = SecuritySession(macAddress, ByteArray(16) { 0x42 })
            )
        )
    }
}
