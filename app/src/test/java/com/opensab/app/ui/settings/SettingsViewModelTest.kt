package com.opensab.app.ui.settings

import app.cash.turbine.test
import com.opensab.app.R
import com.opensab.app.logging.JournalUsageSource
import com.opensab.app.source.music.FakeMediaAppScanner
import com.opensab.app.source.music.InMemoryFavoriteMediaRepository
import com.opensab.app.ui.MainDispatcherRule
import com.opensab.app.ui.permissions.FakePermissionStatusReader
import com.opensab.app.ui.permissions.PermissionCatalog
import com.opensab.app.ui.permissions.PermissionEntry
import com.opensab.app.ui.permissions.PermissionId
import com.opensab.app.ui.permissions.PermissionStatus
import com.opensab.app.ui.permissions.PermissionStatusReader
import com.opensab.domain.music.FakeMediaLibraryBrowser
import com.opensab.domain.music.MediaAppInfo
import com.opensab.domain.music.MediaControllerAdapter
import com.opensab.domain.music.MediaLibraryBrowser
import com.opensab.domain.music.MediaLibraryNode
import com.opensab.domain.music.MockMediaControllerAdapter
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.FakeSettingsRepository
import com.opensab.domain.settings.MuteAction
import com.opensab.domain.settings.SettingsRepository
import com.opensab.domain.settings.ThemeMode
import com.opensab.logging.journal.ExportRequest
import com.opensab.logging.journal.JournalReader
import com.opensab.logging.journal.JournalUsage
import com.opensab.logging.journal.SessionListing
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.crypto.InMemorySecureKeyStore
import com.opensab.sab.session.vehicle.BodyStyle
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.InMemoryVehicleRepository
import com.opensab.sab.session.vehicle.KnownVehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val mac = "AA:BB:CC:11:22:33"

    private val mediaApps = listOf(
        MediaAppInfo("com.spotify.music", "Spotify"),
        MediaAppInfo("com.google.android.apps.youtube.music", "YouTube Music")
    )

    @Test
    fun initialStateLoadsSettingsKnownVehiclesAndInstalledMediaApps() = runTest {
        val vehicleRepo = InMemoryVehicleRepository().apply { saveVehicle(knownVehicle()) }
        val viewModel = createViewModel(
            settingsRepository = FakeSettingsRepository(AppSettings(verboseLogging = true)),
            vehicleRepository = vehicleRepo
        )

        assertEquals(true, viewModel.uiState.value.settings.verboseLogging)
        assertEquals(listOf(mac), viewModel.uiState.value.knownVehicles.map { it.macAddress })
        assertEquals(2, viewModel.uiState.value.installedMediaApps.size)
    }

    @Test
    fun verboseLoggingShowsOnInADebugBuildWhenUnchosen() = runTest {
        val viewModel = createViewModel(settingsRepository = FakeSettingsRepository(AppSettings()), isDebuggable = true)
        runCurrent()
        assertTrue(viewModel.uiState.value.verboseLogging)
    }

    @Test
    fun verboseLoggingShowsOffInAReleaseWhenUnchosen() = runTest {
        val viewModel = createViewModel(settingsRepository = FakeSettingsRepository(AppSettings()), isDebuggable = false)
        runCurrent()
        assertFalse(viewModel.uiState.value.verboseLogging)
    }

    @Test
    fun updateSettingsIntentPersistsChangesInTheRepository() = runTest {
        val settingsRepo = FakeSettingsRepository()
        val viewModel = createViewModel(settingsRepository = settingsRepo)

        viewModel.sendIntent(
            SettingsUiIntent.UpdateSettings {
                it.copy(autoConnect = false, themeMode = ThemeMode.DARK)
            }
        )

        assertFalse(settingsRepo.getSettings().autoConnect)
        assertEquals(ThemeMode.DARK, settingsRepo.getSettings().themeMode)
        assertEquals(
            "The screen renders from the repository stream, not from the intent",
            ThemeMode.DARK,
            viewModel.uiState.value.settings.themeMode
        )
    }

    @Test
    fun setFavoriteMediaPackageWritesBothTheMediaRepositoryAndTheSettings() = runTest {
        val settingsRepo = FakeSettingsRepository()
        val favoriteRepo = InMemoryFavoriteMediaRepository()
        val viewModel = createViewModel(
            settingsRepository = settingsRepo,
            favoriteMediaRepository = favoriteRepo
        )

        viewModel.sendIntent(SettingsUiIntent.SetFavoriteMediaPackage("com.spotify.music"))

        assertEquals("com.spotify.music", favoriteRepo.favoritePackage.first())
        assertEquals("com.spotify.music", settingsRepo.getSettings().favoriteMediaPackage)
        assertEquals("com.spotify.music", viewModel.uiState.value.settings.favoriteMediaPackage)

        viewModel.sendIntent(SettingsUiIntent.SetFavoriteMediaPackage(null))

        assertNull(favoriteRepo.favoritePackage.first())
        assertNull(settingsRepo.getSettings().favoriteMediaPackage)
    }

    @Test
    fun forgetVehicleRemovesTheProfileItsSessionKeyAndTellsTheRider() = runTest {
        val vehicleRepo = InMemoryVehicleRepository().apply { saveVehicle(knownVehicle()) }
        val keyStore = InMemorySecureKeyStore().apply { saveSessionKey(mac, ByteArray(16)) }
        val viewModel = createViewModel(vehicleRepository = vehicleRepo, keyStore = keyStore)

        viewModel.uiEvents.test {
            viewModel.sendIntent(SettingsUiIntent.ForgetVehicle(mac))

            assertEquals(
                SettingsUiEvent.ShowToast(R.string.settings_toast_vehicle_forgotten),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(vehicleRepo.getAllVehicles().isEmpty())
        assertFalse(
            "A forgotten motorcycle must not keep a usable AES session key behind",
            keyStore.hasSessionKey(mac)
        )
        assertTrue(viewModel.uiState.value.knownVehicles.isEmpty())
    }

    @Test
    fun savingAProfileWritesItToTheRepository() = runTest {
        val vehicleRepo = InMemoryVehicleRepository().apply { saveVehicle(knownVehicle()) }
        val viewModel = createViewModel(vehicleRepository = vehicleRepo)

        viewModel.sendIntent(
            SettingsUiIntent.SaveVehicleProfile(
                mac,
                VehicleProfileDraft("Rebelde", BodyStyle.GENERIC, ClusterDisplay.COLOR)
            )
        )

        assertEquals("Rebelde", vehicleRepo.getVehicle(mac)!!.displayName)
    }

    @Test
    fun savingAProfileTellsTheRider() = runTest {
        val vehicleRepo = InMemoryVehicleRepository().apply { saveVehicle(knownVehicle()) }
        val viewModel = createViewModel(vehicleRepository = vehicleRepo)

        viewModel.uiEvents.test {
            viewModel.sendIntent(
                SettingsUiIntent.SaveVehicleProfile(
                    mac,
                    VehicleProfileDraft("Rebelde", BodyStyle.GENERIC, ClusterDisplay.COLOR)
                )
            )

            assertEquals(SettingsUiEvent.ShowToast(R.string.settings_toast_vehicle_saved), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A profile edit racing a `ForgetVehicle` must not bring the motorcycle back: `updateProfile`
     * only writes with the identity record still there, which is why this stays silent instead of
     * toasting a save that never happened.
     */
    @Test
    fun savingAForgottenVehicleDoesNotResurrectIt() = runTest {
        val vehicleRepo = InMemoryVehicleRepository()
        val viewModel = createViewModel(vehicleRepository = vehicleRepo)
        val forgottenMac = "02:00:00:00:00:99"

        viewModel.uiEvents.test {
            viewModel.sendIntent(
                SettingsUiIntent.SaveVehicleProfile(
                    forgottenMac,
                    VehicleProfileDraft("Rebelde", BodyStyle.GENERIC, ClusterDisplay.COLOR)
                )
            )
            expectNoEvents()
        }

        assertNull(vehicleRepo.getVehicle(forgottenMac))
        assertNull(vehicleRepo.getProfile(forgottenMac))
    }

    @Test
    fun resetSessionKeysClearsTheKeyStore() = runTest {
        val keyStore = InMemorySecureKeyStore().apply {
            saveSessionKey(mac, ByteArray(16))
            saveSessionKey("11:22:33:44:55:66", ByteArray(16))
        }
        val viewModel = createViewModel(keyStore = keyStore)

        viewModel.uiEvents.test {
            viewModel.sendIntent(SettingsUiIntent.ResetSessionKeys)
            assertEquals(
                SettingsUiEvent.ShowToast(R.string.settings_toast_session_keys_reset),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }

        assertFalse(keyStore.hasSessionKey(mac))
        assertFalse(keyStore.hasSessionKey("11:22:33:44:55:66"))
    }

    @Test
    fun resetSettingsToDefaultsRestoresTheFactoryValues() = runTest {
        val settingsRepo = FakeSettingsRepository(
            AppSettings(verboseLogging = true, themeMode = ThemeMode.DARK)
        )
        val viewModel = createViewModel(settingsRepository = settingsRepo)

        viewModel.uiEvents.test {
            viewModel.sendIntent(SettingsUiIntent.ResetSettingsToDefaults)
            assertEquals(
                SettingsUiEvent.ShowToast(R.string.settings_toast_settings_reset),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(AppSettings(), settingsRepo.getSettings())
        assertEquals(AppSettings(), viewModel.uiState.value.settings)
    }

    @Test
    fun navigateBackIntentEmitsNavigateBack() = runTest {
        val viewModel = createViewModel()

        viewModel.uiEvents.test {
            viewModel.sendIntent(SettingsUiIntent.NavigateBack)
            assertTrue(awaitItem() is SettingsUiEvent.NavigateBack)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // region Session log

    @Test
    fun theJournalSectionIsHiddenInRelease() = runTest {
        val viewModel = createViewModel(isDebuggable = false)
        runCurrent()

        assertNull(viewModel.uiState.value.journal)
    }

    @Test
    fun theJournalSectionShowsUsageAgainstTheLimit() = runTest {
        val usage = MutableStateFlow(JournalUsage(13_002_342, 50L shl 20))
        val viewModel = createViewModel(isDebuggable = true, usage = usage)
        runCurrent()

        assertEquals(
            JournalSettingsUi(13_002_342, 50, overLimit = false),
            viewModel.uiState.value.journal
        )
    }

    @Test
    fun theJournalSectionJudgesOverLimitAgainstTheStoredSettingNotTheJournalsUnboundedLimit() = runTest {
        val settingsRepo = FakeSettingsRepository(AppSettings(sessionLogLimitMb = 50))
        val usage = MutableStateFlow(JournalUsage(60L shl 20, Long.MAX_VALUE))
        val viewModel = createViewModel(
            settingsRepository = settingsRepo,
            isDebuggable = true,
            usage = usage
        )
        runCurrent()

        assertEquals(
            JournalSettingsUi(60L shl 20, 50, overLimit = true),
            viewModel.uiState.value.journal
        )
    }

    @Test
    fun theJournalSectionNormalizesAnOutOfRangeStoredLimit() = runTest {
        val settingsRepo = FakeSettingsRepository(AppSettings(sessionLogLimitMb = 37))
        val usage = MutableStateFlow(JournalUsage(0, 50L shl 20))
        val viewModel = createViewModel(
            settingsRepository = settingsRepo,
            isDebuggable = true,
            usage = usage
        )
        runCurrent()

        assertEquals(50, viewModel.uiState.value.journal?.limitMb)
    }

    @Test
    fun choosingALimitStoresIt() = runTest {
        val settingsRepo = FakeSettingsRepository()
        val viewModel = createViewModel(settingsRepository = settingsRepo, isDebuggable = true)

        viewModel.sendIntent(SettingsUiIntent.SetSessionLogLimit(100))
        runCurrent()

        assertEquals(100, settingsRepo.getSettings().sessionLogLimitMb)
    }

    @Test
    fun openSessionsNavigates() = runTest {
        val viewModel = createViewModel(isDebuggable = true)

        viewModel.uiEvents.test {
            viewModel.sendIntent(SettingsUiIntent.OpenSessions)
            runCurrent()
            assertEquals(SettingsUiEvent.NavigateToSessions, awaitItem())
        }
    }

    // endregion

    /**
     * The settings and vehicle streams run under `whileSubscribed()`, so they only start once
     * something observes `uiState`.
     */
    // region Permissions row

    @Test
    fun `should count the granted permissions against the ones that can be granted`() = runTest {
        val viewModel = createViewModel(permissionStatusReader = readerWithThreeOfTwelveGranted())

        assertEquals(PermissionSummary(granted = 3, total = 12), viewModel.uiState.value.permissions)
    }

    /** An `UNSUPPORTED` row leaves **both** numbers, not just the numerator. */
    @Test
    fun `should leave the unsupported ones out of both numbers`() = runTest {
        val entries = PermissionCatalog.entriesFor(sdkInt = 36)
        val allUnsupported = FakePermissionStatusReader(fallback = PermissionStatus.UNSUPPORTED)
        val viewModel = createViewModel(permissionStatusReader = allUnsupported)

        assertEquals(13, entries.size)
        assertEquals(PermissionSummary(granted = 0, total = 0), viewModel.uiState.value.permissions)
    }

    @Test
    fun `should recount when the rider comes back from the permissions screen`() = runTest {
        val reader = FakePermissionStatusReader(fallback = PermissionStatus.DENIED)
        val viewModel = createViewModel(permissionStatusReader = reader)
        assertEquals(
            PermissionSummary(granted = 0, total = 13),
            viewModel.uiState.value.permissions
        )

        reader.fallback = PermissionStatus.GRANTED
        viewModel.sendIntent(SettingsUiIntent.RefreshPermissions)

        assertEquals(
            PermissionSummary(granted = 13, total = 13),
            viewModel.uiState.value.permissions
        )
    }

    @Test
    fun `should ask to open the permissions screen`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiEvents.test {
            viewModel.sendIntent(SettingsUiIntent.OpenPermissions)

            assertEquals(SettingsUiEvent.NavigateToPermissions, awaitItem())
        }
    }

    @Test
    fun `should ask to open the cluster menu screen`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiEvents.test {
            viewModel.sendIntent(SettingsUiIntent.OpenRootMenu)

            assertEquals(SettingsUiEvent.NavigateToRootMenu, awaitItem())
        }
    }

    /** Three granted, one impossible, nine still to go: 3 of 12 out of a thirteen-row catalogue. */
    private fun readerWithThreeOfTwelveGranted() = FakePermissionStatusReader(
        statuses = mapOf(
            PermissionId.BLUETOOTH to PermissionStatus.GRANTED,
            PermissionId.BLUETOOTH_ENABLED to PermissionStatus.GRANTED,
            PermissionId.NOTIFICATION_ACCESS to PermissionStatus.GRANTED,
            PermissionId.ANSWER_CALLS to PermissionStatus.UNSUPPORTED
        ),
        fallback = PermissionStatus.DENIED
    )

    // endregion

    @Test
    fun coldStartCountdownDecrementsAndTriggersPlay() = runTest {
        val fakeAdapter = MockMediaControllerAdapter()
        val viewModel = createViewModel(mediaControllerAdapter = fakeAdapter)

        viewModel.sendIntent(SettingsUiIntent.StartColdStartCountdown)
        testScheduler.runCurrent()
        assertEquals(5, viewModel.uiState.value.coldStartCountdown)
        assertEquals("Adapter must be attached during cold start countdown", 1, fakeAdapter.startCount)

        testScheduler.advanceTimeBy(1.seconds)
        testScheduler.runCurrent()
        assertEquals(4, viewModel.uiState.value.coldStartCountdown)

        testScheduler.advanceTimeBy(4.seconds)
        testScheduler.runCurrent()
        assertEquals(null, viewModel.uiState.value.coldStartCountdown)
        assertEquals(1, fakeAdapter.playCount)
    }

    @Test
    fun openingThePickerConnectsAndListsTheRoot() = runTest {
        val browser = FakeMediaLibraryBrowser().apply {
            rootId = "ROOT_AUTO"
            children["ROOT_AUTO"] = listOf(
                MediaLibraryNode("MY_COLLECTION", "Colección", null, isPlayable = false, isBrowsable = true)
            )
        }
        val viewModel = createViewModel(
            settingsRepository = FakeSettingsRepository(
                AppSettings(favoriteMediaPackage = "com.aspiro.tidal")
            ),
            libraryBrowser = browser
        )

        viewModel.sendIntent(SettingsUiIntent.OpenColdStartPicker)
        testScheduler.runCurrent()

        assertEquals("com.aspiro.tidal", browser.lastConnectedPackage)
        val picker = viewModel.uiState.value.libraryPicker
        assertNotNull(picker)
        assertEquals(listOf("MY_COLLECTION"), picker!!.nodes.map { it.mediaId })
        assertEquals(listOf("ROOT_AUTO"), picker.stack.map { it.mediaId })
    }

    @Test
    fun browsingAContainerPushesItOntoTheStack() = runTest {
        val browser = FakeMediaLibraryBrowser().apply {
            rootId = "ROOT_AUTO"
            children["ROOT_AUTO"] = listOf(
                MediaLibraryNode("MY_COLLECTION", "Colección", null, isPlayable = false, isBrowsable = true)
            )
            children["MY_COLLECTION"] = listOf(
                MediaLibraryNode("MY_COLLECTION_TRACKS", "Canciones", null, isPlayable = true, isBrowsable = false)
            )
        }
        val viewModel = createViewModel(
            settingsRepository = FakeSettingsRepository(
                AppSettings(favoriteMediaPackage = "com.aspiro.tidal")
            ),
            libraryBrowser = browser
        )
        viewModel.sendIntent(SettingsUiIntent.OpenColdStartPicker)
        testScheduler.runCurrent()

        viewModel.sendIntent(SettingsUiIntent.BrowseColdStartNode("MY_COLLECTION", "Colección"))
        testScheduler.runCurrent()

        val picker = viewModel.uiState.value.libraryPicker!!
        assertEquals(listOf("MY_COLLECTION_TRACKS"), picker.nodes.map { it.mediaId })
        assertEquals(listOf("ROOT_AUTO", "MY_COLLECTION"), picker.stack.map { it.mediaId })
    }

    @Test
    fun backAtTheRootDisconnectsAndClosesThePicker() = runTest {
        val browser = FakeMediaLibraryBrowser().apply { rootId = "ROOT_AUTO" }
        val viewModel = createViewModel(
            settingsRepository = FakeSettingsRepository(
                AppSettings(favoriteMediaPackage = "com.aspiro.tidal")
            ),
            libraryBrowser = browser
        )
        viewModel.sendIntent(SettingsUiIntent.OpenColdStartPicker)
        testScheduler.runCurrent()

        viewModel.sendIntent(SettingsUiIntent.ColdStartPickerBack)
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.libraryPicker)
        assertEquals(1, browser.disconnectCount)
    }

    @Test
    fun pinningWritesTheIdLabelAndPackage() = runTest {
        val settingsRepo = FakeSettingsRepository(
            AppSettings(favoriteMediaPackage = "com.aspiro.tidal")
        )
        val viewModel = createViewModel(
            settingsRepository = settingsRepo,
            libraryBrowser = FakeMediaLibraryBrowser().apply { rootId = "ROOT_AUTO" }
        )
        viewModel.sendIntent(SettingsUiIntent.OpenColdStartPicker)
        testScheduler.runCurrent()

        viewModel.sendIntent(SettingsUiIntent.PinColdStartMedia("MY_COLLECTION_TRACKS", "Canciones"))
        testScheduler.runCurrent()

        assertEquals("MY_COLLECTION_TRACKS", settingsRepo.getSettings().coldStartMediaId)
        assertEquals("Canciones", settingsRepo.getSettings().coldStartMediaLabel)
        assertEquals("com.aspiro.tidal", settingsRepo.getSettings().coldStartMediaPackage)
        assertNull("Pinning closes the picker", viewModel.uiState.value.libraryPicker)
    }

    @Test
    fun aFailedConnectionClosesThePickerAndWritesNothing() = runTest {
        val settingsRepo = FakeSettingsRepository(
            AppSettings(favoriteMediaPackage = "com.aspiro.tidal")
        )
        val viewModel = createViewModel(
            settingsRepository = settingsRepo,
            libraryBrowser = FakeMediaLibraryBrowser().apply { rootId = null }
        )

        viewModel.sendIntent(SettingsUiIntent.OpenColdStartPicker)
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.libraryPicker)
        assertNull(settingsRepo.getSettings().coldStartMediaId)
    }

    @Test
    fun aRejectedConnectionMarksThePlayerAsUnbrowsable() = runTest {
        // Spotify answers the bind and then refuses the root ("No root for client com.opensab"),
        // which reaches us as a null from connect. The row has to stop inviting the tap.
        val viewModel = createViewModel(
            settingsRepository = FakeSettingsRepository(
                AppSettings(favoriteMediaPackage = "com.spotify.music")
            ),
            libraryBrowser = FakeMediaLibraryBrowser().apply { rootId = null }
        )

        viewModel.sendIntent(SettingsUiIntent.OpenColdStartPicker)
        testScheduler.runCurrent()

        assertEquals("com.spotify.music", viewModel.uiState.value.libraryUnavailableFor)
    }

    @Test
    fun choosingAnotherPlayerClearsTheUnbrowsableMark() = runTest {
        // The mark belongs to a package, not to the app, so switching favourites must not carry
        // Spotify's refusal over to a player that has never been asked.
        val viewModel = createViewModel(
            settingsRepository = FakeSettingsRepository(
                AppSettings(favoriteMediaPackage = "com.spotify.music")
            ),
            libraryBrowser = FakeMediaLibraryBrowser().apply { rootId = null }
        )
        viewModel.sendIntent(SettingsUiIntent.OpenColdStartPicker)
        testScheduler.runCurrent()
        assertEquals("com.spotify.music", viewModel.uiState.value.libraryUnavailableFor)

        viewModel.sendIntent(SettingsUiIntent.SetFavoriteMediaPackage("com.aspiro.tidal"))
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.libraryUnavailableFor)
    }

    @Test
    fun aPlayerThatAnswersLeavesNoUnbrowsableMark() = runTest {
        val viewModel = createViewModel(
            settingsRepository = FakeSettingsRepository(
                AppSettings(favoriteMediaPackage = "com.aspiro.tidal")
            ),
            libraryBrowser = FakeMediaLibraryBrowser().apply { rootId = "ROOT_AUTO" }
        )

        viewModel.sendIntent(SettingsUiIntent.OpenColdStartPicker)
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.libraryUnavailableFor)
    }

    private fun TestScope.createViewModel(
        settingsRepository: SettingsRepository = FakeSettingsRepository(),
        vehicleRepository: InMemoryVehicleRepository = InMemoryVehicleRepository(),
        favoriteMediaRepository: InMemoryFavoriteMediaRepository = InMemoryFavoriteMediaRepository(),
        keyStore: InMemorySecureKeyStore = InMemorySecureKeyStore(),
        permissionEntries: List<PermissionEntry> = PermissionCatalog.entriesFor(sdkInt = 36),
        permissionStatusReader: PermissionStatusReader = FakePermissionStatusReader(),
        isDebuggable: Boolean = false,
        usage: MutableStateFlow<JournalUsage> = MutableStateFlow(JournalUsage(0, 0)),
        mediaControllerAdapter: MediaControllerAdapter = MockMediaControllerAdapter(),
        libraryBrowser: MediaLibraryBrowser = FakeMediaLibraryBrowser()
    ): SettingsViewModel {
        val reader = FakeJournalReader(usage)
        backgroundScope.launch(mainDispatcherRule.testDispatcher) {
            usage.collect { reader.notifyListener(it) }
        }
        return mainDispatcherRule.register(
            SettingsViewModel(
                settingsRepository = settingsRepository,
                vehicleRepository = vehicleRepository,
                favoriteMediaRepository = favoriteMediaRepository,
                keyStore = keyStore,
                mediaAppScanner = FakeMediaAppScanner(mediaApps),
                permissionEntries = permissionEntries,
                permissionStatusReader = permissionStatusReader,
                journalUsage = JournalUsageSource(reader),
                isDebuggable = isDebuggable,
                mediaControllerAdapter = mediaControllerAdapter,
                libraryBrowser = libraryBrowser
            )
        ).also { viewModel ->
            backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect {} }
        }
    }

    /** Its listener fires from the test's [MutableStateFlow], as the real journal fires its own. */
    private class FakeJournalReader(private val source: MutableStateFlow<JournalUsage>) : JournalReader {
        override val usage: JournalUsage get() = source.value
        private var listener: ((JournalUsage) -> Unit)? = null

        override fun setUsageListener(listener: ((JournalUsage) -> Unit)?) {
            this.listener = listener
        }

        fun notifyListener(next: JournalUsage) {
            listener?.invoke(next)
        }

        override fun listSessions(callback: (List<SessionListing>) -> Unit) = callback(emptyList())

        override fun export(request: ExportRequest, callback: (Result<File>) -> Unit) =
            callback(Result.failure(UnsupportedOperationException()))
    }

    private fun knownVehicle() = KnownVehicle(
        macAddress = mac,
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

    @Test
    fun updateMuteActionEmitsNewState() = runTest {
        val settingsRepo = FakeSettingsRepository()
        val viewModel = createViewModel(settingsRepository = settingsRepo)
        viewModel.sendIntent(SettingsUiIntent.UpdateSettings { it.copy(muteAction = MuteAction.MUTE_ALL) })
        assertEquals(MuteAction.MUTE_ALL, viewModel.uiState.value.settings.muteAction)
    }
}
