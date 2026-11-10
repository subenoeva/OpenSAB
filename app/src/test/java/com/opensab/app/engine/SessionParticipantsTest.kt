package com.opensab.app.engine

import com.opensab.app.di.OrchestratorModule
import com.opensab.app.source.music.InMemoryFavoriteMediaRepository
import com.opensab.cluster.BaseStateManager
import com.opensab.cluster.StubPageProvider
import com.opensab.cluster.allRootMenuEntries
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.cluster.rootmenu.RootMenuPageProvider
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.call.CallNotificationBus
import com.opensab.domain.call.DefaultCallManager
import com.opensab.domain.message.MockMessageSource
import com.opensab.domain.music.DefaultMusicManager
import com.opensab.domain.music.MockMediaControllerAdapter
import com.opensab.domain.music.MusicManager
import com.opensab.domain.music.RecordingMusicManager
import com.opensab.domain.nav.DefaultNavigationManager
import com.opensab.domain.nav.NavigationNotificationBus
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.FakeSettingsRepository
import com.opensab.domain.weather.MockWeatherSource
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.CloseReason
import com.opensab.sab.session.SabSession
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.SessionParticipant
import com.opensab.sab.session.closeForTest
import com.opensab.sab.session.input.DefaultInputDispatcher
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.DefaultControlPointGateway
import com.opensab.sab.session.projection.DefaultScreenProjector
import com.opensab.sab.session.vehicle.InMemoryVehicleRepository
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The start order — motor before sources, so the carousel exists before anything tries to redraw
 * it — and the reverse-close discipline every [SessionParticipant] inherits from
 * [SabSession.onClose].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionParticipantsTest {

    private class Composition(val participants: List<SessionParticipant>, val translatorNames: List<String>)

    private fun composition(
        musicManager: MusicManager = DefaultMusicManager(MockMediaControllerAdapter()),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        favorite: InMemoryFavoriteMediaRepository = InMemoryFavoriteMediaRepository()
    ): Composition {
        val controlPointGateway = DefaultControlPointGateway()
        val requestIdGenerator = RequestIdGenerator()
        val inputDispatcher = DefaultInputDispatcher(controlPointGateway)
        val screenProjector = DefaultScreenProjector(requestIdGenerator, controlPointGateway)
        val indicators = ActiveIndicatorsPublisher(screenProjector)
        val capabilities = VehicleCapabilitiesHolder()
        val baseStateManager = BaseStateManager(
            screenProjector = screenProjector,
            requestIdGenerator = requestIdGenerator,
            capabilities = capabilities,
            activeIndicators = indicators,
            projectionMirror = ProjectionMirror(),
            rootPageFactory = {
                RootMenuPageProvider(
                    entries = allRootMenuEntries(),
                    pageFactory = { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) },
                    copy = englishClusterText
                )
            }
        )

        val navManager = DefaultNavigationManager(NavigationNotificationBus())
        val callManager = DefaultCallManager(CallNotificationBus())
        val messageSource = MockMessageSource()
        val translators = OrchestratorModule.provideIndicatorTranslators(
            musicManager = musicManager,
            navManager = navManager,
            callManager = callManager,
            messageSource = messageSource,
            activeIndicators = indicators
        )

        val participants = OrchestratorModule.provideSessionParticipants(
            baseStateManager = baseStateManager,
            inputDispatcher = inputDispatcher,
            musicManager = musicManager,
            navManager = navManager,
            callManager = callManager,
            weatherSource = MockWeatherSource(),
            messageSource = messageSource,
            indicators = translators,
            vehicleRepository = InMemoryVehicleRepository(),
            settingsRepository = settings,
            favoriteMediaRepository = favorite
        )

        return Composition(participants, translators.map { it.name })
    }

    @Test
    fun `the engine, the five sources, the indicators and the known vehicle take part in start order`() {
        val composition = composition()
        val participants = composition.participants
        assertEquals(
            listOf(
                "engine", "music", "auto-resume", "navigation", "call", "weather", "message", "indicators",
                "known-vehicle"
            ),
            participants.map { it.name }
        )
        // The translators start in the same order as the sources they read.
        assertEquals(listOf("music", "navigation", "call", "message"), composition.translatorNames)
    }

    @Test
    fun `the real composition plays the favourite once at session start when the setting is on`() = runTest {
        val music = RecordingMusicManager()
        val participants = composition(
            musicManager = music,
            settings = FakeSettingsRepository(AppSettings(autoResumeMusicOnConnect = true)),
            favorite = InMemoryFavoriteMediaRepository("com.spotify.music")
        ).participants
        val session = SabSession(
            id = 1,
            macAddress = "AA:BB:CC:DD:EE:FF",
            origin = SessionOrigin.RECEIVER,
            parent = backgroundScope.coroutineContext,
            stallThreshold = null,
            onCloseRequested = { _, _ -> }
        )

        participants.single { it.name == "auto-resume" }.onSessionStarted(session)
        runCurrent()

        assertEquals(1, music.playCalls)
        session.closeForTest(CloseReason.DISCONNECT)
    }

    @Test
    fun `closing goes in reverse order`() = runTest {
        val started = mutableListOf<String>()
        val closed = mutableListOf<String>()
        fun recording(participantName: String) = object : SessionParticipant {
            override val name: String = participantName
            override fun onSessionStarted(session: SabSession) {
                started += participantName
            }
            override fun onSessionClosing(session: SabSession) {
                closed += participantName
            }
        }
        val participants = listOf(recording("engine"), recording("music"), recording("navigation"))
        val session = SabSession(
            id = 1,
            macAddress = "AA:BB:CC:DD:EE:FF",
            origin = SessionOrigin.RECEIVER,
            parent = backgroundScope.coroutineContext,
            stallThreshold = null,
            onCloseRequested = { _, _ -> }
        )

        // The exact pattern SabSessionController follows: start, then register the close right away.
        participants.forEach { participant ->
            participant.onSessionStarted(session)
            session.onClose(participant.name) { participant.onSessionClosing(session) }
        }
        session.closeForTest(CloseReason.DISCONNECT)

        assertEquals(listOf("engine", "music", "navigation"), started)
        assertEquals(listOf("navigation", "music", "engine"), closed)
    }
}
