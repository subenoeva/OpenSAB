package com.opensab.app.di

import com.opensab.cluster.DefaultPageProviderFactory
import com.opensab.cluster.PageVoice
import com.opensab.cluster.allRootMenuEntries
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.cluster.music.volumeControlOn
import com.opensab.cluster.text.englishPresentation
import com.opensab.domain.audio.NoAudioFocus
import com.opensab.domain.audio.NoCuePlayer
import com.opensab.domain.call.CallManager
import com.opensab.domain.call.CallNotificationBus
import com.opensab.domain.call.MockCallDialer
import com.opensab.domain.call.MockTelecomAdapter
import com.opensab.domain.call.TelecomAdapter
import com.opensab.domain.contacts.MockCallMenuAccess
import com.opensab.domain.contacts.MockContactDirectory
import com.opensab.domain.message.MockMessageMenuAccess
import com.opensab.domain.message.MockMessageSource
import com.opensab.domain.message.MockSmsSender
import com.opensab.domain.music.MockMediaControllerAdapter
import com.opensab.domain.music.RecordingVolumeSource
import com.opensab.domain.nav.FakeDeviceUnlockSource
import com.opensab.domain.nav.MockNavigationLauncher
import com.opensab.domain.nav.NavigationNotificationBus
import com.opensab.domain.settings.FakeSettingsRepository
import com.opensab.domain.speech.MockSpeechRecognizer
import com.opensab.domain.speech.MockSpeechSynthesizer
import com.opensab.domain.weather.MockWeatherSource
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.PopUpPriority
import com.opensab.sab.session.projection.QueuedPopUp
import com.opensab.sab.session.projection.RecordingScreenProjector
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.KnownVehicle
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Guards the wiring of [VehicleCapabilitiesHolder] through [OrchestratorModule].
 *
 * The holder is what carries the connected cluster's limits from pipeline step 4 to everything
 * that draws, and it only works while every consumer shares one instance. Nothing about that is
 * visible at a call site: a provider handed its own holder would compile, pass every other test,
 * and simply stop degrading — the motorcycle would take full colour on a segment panel and no
 * failure would ever be reported. So the invariant is asserted here rather than trusted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilitiesWiringTest {

    private fun segmentClusterVehicle(): KnownVehicle = KnownVehicle(
        macAddress = "02:00:00:12:34:56",
        displayName = "Honda 052B",
        vehicleInfo = VehicleInformation(
            vehicleType = 0x01,
            displayCapability = byteArrayOf(0x01, 0x02, 0x03),
            switchType = SwitchType.TYPE_A,
            categoryId = 0x01,
            seriesCode = "052B",
            destinationId = DestinationId.EU
        ),
        clusterDisplay = ClusterDisplay.SEGMENT
    )

    private fun createTestPageFactory(
        callManager: CallManager,
        telecomAdapter: TelecomAdapter
    ): DefaultPageProviderFactory = DefaultPageProviderFactory(
        musicManager = OrchestratorModule.provideMusicManager(MockMediaControllerAdapter()),
        navManager = OrchestratorModule.provideNavManager(NavigationNotificationBus()),
        callManager = callManager,
        weatherSource = MockWeatherSource(),
        messageSource = MockMessageSource(),
        telecomAdapter = telecomAdapter,
        volumeSource = RecordingVolumeSource(),
        speechRecognizer = MockSpeechRecognizer(),
        contactDirectory = MockContactDirectory(),
        callDialer = MockCallDialer(),
        callMenuAccess = MockCallMenuAccess(),
        smsSender = MockSmsSender(),
        messageMenuAccess = MockMessageMenuAccess(),
        navigationLauncher = MockNavigationLauncher(),
        deviceUnlockSource = FakeDeviceUnlockSource(),
        quickReplies = MutableStateFlow(emptyList()),
        sentLinger = MutableStateFlow(2.seconds),
        rootMenuEntries = allRootMenuEntries(),
        handlebarVolumeControl = volumeControlOn,
        inboundCallMode = FakeSettingsRepository().settingsFlow.map { it.inboundCallMode }.distinctUntilChanged(),
        inboundMessageMode = FakeSettingsRepository().settingsFlow.map { it.inboundMessageMode }.distinctUntilChanged(),
        presentation = englishPresentation
    )

    @Test
    fun `the engine the module builds reads the holder the orchestrator adopts into`() = runTest {
        val holder = VehicleCapabilitiesHolder()
        val projector = RecordingScreenProjector()
        val indicators = ActiveIndicatorsPublisher(projector)
        val generator = RequestIdGenerator()
        val telecomAdapter = MockTelecomAdapter()
        val callManager = OrchestratorModule.provideDefaultCallManager(NoAudioFocus, CallNotificationBus())

        val engine = OrchestratorModule.provideBaseStateManager(
            screenProjector = projector,
            requestIdGenerator = generator,
            capabilities = holder,
            activeIndicators = indicators,
            projectionMirror = ProjectionMirror(),
            pageVoice = PageVoice({ "" }, MockSpeechSynthesizer(), sessionScope = backgroundScope),
            cuePlayer = NoCuePlayer,
            pageFactory = createTestPageFactory(callManager, telecomAdapter)
        )
        engine.popupManager.start(backgroundScope)

        // What DefaultSabConnectionOrchestrator does right after reading vehicle identity.
        holder.adopt(segmentClusterVehicle())

        engine.popupManager.post(
            QueuedPopUp(
                id = "incoming-call",
                message = "Llamada",
                icon = IconId.INCOMING_CALL,
                priority = PopUpPriority.INCOMING_CALL
            )
        )

        // Had the engine been built with a holder of its own, this modal would have gone out as a
        // modal onto a cluster with no layer to draw one on.
        assertTrue("No modal may reach a segment cluster", projector.popUps.isEmpty())
        assertEquals(ActiveIndicator.PHONE.bit, indicators.mask)
    }

    @Test
    fun `a holder nobody has adopted into degrades nothing`() = runTest {
        val holder = VehicleCapabilitiesHolder()
        val projector = RecordingScreenProjector()
        val indicators = ActiveIndicatorsPublisher(projector)
        val generator = RequestIdGenerator()
        val telecomAdapter = MockTelecomAdapter()
        val callManager = OrchestratorModule.provideDefaultCallManager(NoAudioFocus, CallNotificationBus())

        val engine = OrchestratorModule.provideBaseStateManager(
            screenProjector = projector,
            requestIdGenerator = generator,
            capabilities = holder,
            activeIndicators = indicators,
            projectionMirror = ProjectionMirror(),
            pageVoice = PageVoice({ "" }, MockSpeechSynthesizer(), sessionScope = backgroundScope),
            cuePlayer = NoCuePlayer,
            pageFactory = createTestPageFactory(callManager, telecomAdapter)
        )
        engine.popupManager.start(backgroundScope)

        engine.popupManager.post(QueuedPopUp(id = "incoming-call", message = "Llamada", icon = IconId.INCOMING_CALL))

        // Before a motorcycle is identified the baseline is permissive, so an app that never
        // connected behaves exactly as it did before the catalog existed.
        assertEquals(1, projector.popUps.size)
        assertEquals(0, indicators.mask)
    }
}
