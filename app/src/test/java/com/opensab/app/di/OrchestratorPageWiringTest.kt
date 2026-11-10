package com.opensab.app.di

import com.opensab.cluster.DefaultPageProviderFactory
import com.opensab.cluster.PageProvider
import com.opensab.cluster.PageProviderFactory
import com.opensab.cluster.PageVoice
import com.opensab.cluster.allRootMenuEntries
import com.opensab.cluster.call.CallMenuPageProvider
import com.opensab.cluster.call.CallPageProvider
import com.opensab.cluster.message.MessageMenuPageProvider
import com.opensab.cluster.message.MessagePageProvider
import com.opensab.cluster.message.MessagePromptPageProvider
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.cluster.music.MusicLoadingPageProvider
import com.opensab.cluster.music.MusicPageProvider
import com.opensab.cluster.music.VolumePageProvider
import com.opensab.cluster.music.volumeControlOn
import com.opensab.cluster.nav.NavMenuPageProvider
import com.opensab.cluster.nav.NavigationPageProvider
import com.opensab.cluster.rootmenu.RootMenuItem
import com.opensab.cluster.text.englishPresentation
import com.opensab.cluster.weather.WeatherPageProvider
import com.opensab.domain.audio.NoCuePlayer
import com.opensab.domain.call.CallNotificationBus
import com.opensab.domain.call.CallNotificationDriver
import com.opensab.domain.call.DefaultCallManager
import com.opensab.domain.call.MockCallDialer
import com.opensab.domain.call.RecordingTelecomAdapter
import com.opensab.domain.contacts.MockCallMenuAccess
import com.opensab.domain.contacts.MockContactDirectory
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.message.MockMessageMenuAccess
import com.opensab.domain.message.MockMessageSource
import com.opensab.domain.message.MockSmsSender
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.RecordingMusicManager
import com.opensab.domain.music.RecordingVolumeSource
import com.opensab.domain.music.TrackMetadata
import com.opensab.domain.nav.FakeDeviceUnlockSource
import com.opensab.domain.nav.ManeuverType
import com.opensab.domain.nav.MockNavigationLauncher
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.nav.RecordingNavigationManager
import com.opensab.domain.settings.FakeSettingsRepository
import com.opensab.domain.speech.MockSpeechRecognizer
import com.opensab.domain.speech.MockSpeechSynthesizer
import com.opensab.domain.weather.MockWeatherSource
import com.opensab.domain.weather.WeatherConditionType
import com.opensab.domain.weather.WeatherInfo
import com.opensab.domain.weather.WmoCaption
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.input.FakeInputDispatcher
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.RecordingScreenProjector
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What `SELECT` opens on each carousel entry, checked through the real engine.
 *
 * `OrchestratorModule` is a Kotlin `object`, so the very factory Hilt calls can be called from the
 * JVM with the doubles of `EngineTestDoubles`. That matters: every provider test can be green while
 * an entry does nothing, because nothing else exercises the wiring itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorPageWiringTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val barcelona = WeatherInfo(
        locationName = "Barcelona",
        temperature = 24,
        temperatureUnit = "°C",
        caption = WmoCaption.CLEAR,
        type = WeatherConditionType.SUNNY
    )

    private class Harness(scope: CoroutineScope) {
        val projector = RecordingScreenProjector()
        val music = RecordingMusicManager()
        val navigation = RecordingNavigationManager()
        val telecom = RecordingTelecomAdapter()
        /** `interceptIncomingCalls` lives here, and the engine reads it. */
        val settings = FakeSettingsRepository()
        val volume = RecordingVolumeSource()
        val indicators = ActiveIndicatorsPublisher(projector)
        val callBus = CallNotificationBus()
        val callDriver = CallNotificationDriver(callBus)
        val callManager = DefaultCallManager(callBus)
        val weather = MockWeatherSource()
        val message = MockMessageSource()
        val smsSender = MockSmsSender()
        val messageMenuAccess = MockMessageMenuAccess()
        val input = FakeInputDispatcher()

        val pageFactory: PageProviderFactory = DefaultPageProviderFactory(
            musicManager = music,
            navManager = navigation,
            callManager = callManager,
            weatherSource = weather,
            messageSource = message,
            telecomAdapter = telecom,
            volumeSource = volume,
            speechRecognizer = MockSpeechRecognizer(),
            contactDirectory = MockContactDirectory(),
            callDialer = MockCallDialer(),
            callMenuAccess = MockCallMenuAccess(),
            smsSender = smsSender,
            messageMenuAccess = messageMenuAccess,
            navigationLauncher = MockNavigationLauncher(),
            deviceUnlockSource = FakeDeviceUnlockSource(),
            quickReplies = MutableStateFlow(emptyList()),
            sentLinger = MutableStateFlow(2.seconds),
            rootMenuEntries = allRootMenuEntries(),
            handlebarVolumeControl = volumeControlOn,
            inboundCallMode = settings.settingsFlow.map { it.inboundCallMode }.distinctUntilChanged(),
            inboundMessageMode = settings.settingsFlow.map { it.inboundMessageMode }.distinctUntilChanged(),
            presentation = englishPresentation
        )

        val manager = OrchestratorModule.provideBaseStateManager(
            screenProjector = projector,
            requestIdGenerator = RequestIdGenerator(),
            capabilities = VehicleCapabilitiesHolder(),
            activeIndicators = indicators,
            projectionMirror = ProjectionMirror(),
            pageVoice = PageVoice({ "" }, MockSpeechSynthesizer(), sessionScope = scope),
            cuePlayer = NoCuePlayer,
            pageFactory = pageFactory
        )

        init {
            callManager.start(scope)
            manager.start(input, scope)
        }

        /**
         * The real sequence: a message arrives and takes the screen, then
         * `BACK` gives the screen back with the message still unread — which is what leaves the
         * rider on the carousel with the MESSAGE entry open.
         */
        suspend fun arriveAndYield() {
            input.enterMode(DashboardMode.SAB_MODE)
            message.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))
            message.yieldScreen()
        }

        /** Mounts the root menu, walks the cursor onto [item] and presses SELECT. */
        suspend fun open(item: RootMenuItem): PageProvider? {
            input.enterMode(DashboardMode.SAB_MODE)
            repeat(RootMenuItem.entries.indexOf(item)) { input.press(FunctionId.NEXT) }
            input.press(FunctionId.SELECT)
            return manager.stack.peek()
        }
    }

    @Test
    fun selectOpensTheMusicLoadingPageWhenMusicIsCold() = testScope.runTest {
        val page = Harness(backgroundScope).open(RootMenuItem.MUSIC)
        assertTrue("expected a MusicLoadingPageProvider, got $page", page is MusicLoadingPageProvider)
    }

    @Test
    fun selectOpensTheMusicPageWhenMusicIsWarm() = testScope.runTest {
        val harness = Harness(backgroundScope)
        harness.music.state.value = MusicState(
            status = PlaybackStatus.PLAYING,
            metadata = TrackMetadata(title = "Bohemian Rhapsody", artist = "Queen")
        )
        val page = harness.open(RootMenuItem.MUSIC)
        assertTrue("expected a MusicPageProvider, got $page", page is MusicPageProvider)
    }

    @Test
    fun selectOpensTheVolumePage() = testScope.runTest {
        val page = Harness(backgroundScope).open(RootMenuItem.VOLUME)
        assertTrue("expected a VolumePageProvider, got $page", page is VolumePageProvider)
    }

    @Test
    fun selectOpensTheNavMenuPageWhenThereIsNoActiveGuidance() = testScope.runTest {
        val page = Harness(backgroundScope).open(RootMenuItem.NAVIGATION)
        assertTrue("expected a NavMenuPageProvider, got $page", page is NavMenuPageProvider)
    }

    @Test
    fun selectOpensTheNavigationPageWhenThereIsActiveGuidance() = testScope.runTest {
        val harness = Harness(backgroundScope)
        harness.navigation.updateInstruction(
            NavInstruction(
                title = "Turn right",
                streetName = "Gran Via",
                distanceFormatted = "300 m",
                maneuver = ManeuverType.TURN_RIGHT
            )
        )
        val page = harness.open(RootMenuItem.NAVIGATION)
        assertTrue("expected a NavigationPageProvider, got $page", page is NavigationPageProvider)
    }

    @Test
    fun selectOpensTheCallMenuPageWhenThereIsNoCall() = testScope.runTest {
        val page = Harness(backgroundScope).open(RootMenuItem.CALL)
        assertTrue("expected a CallMenuPageProvider, got $page", page is CallMenuPageProvider)
    }

    /**
     * The page opens with no reading, because its SELECT is the only way to ask for one when the
     * phone had no fix at session start.
     */
    @Test
    fun theWeatherEntryOpensWithoutAForecast() = testScope.runTest {
        val page = Harness(backgroundScope).open(RootMenuItem.WEATHER)
        assertEquals("WEATHER must open its page even with no reading", "WeatherPage", page?.name)
    }

    /**
     * And it opens on its own once there is one: `canEnter` governs the arrow, the press and the
     * OPEN zone of the mirror off the same property, so a real source lights all three at once.
     */
    @Test
    fun selectOpensTheWeatherPageOnceThereIsAForecast() = testScope.runTest {
        val harness = Harness(backgroundScope)
        harness.weather.updateWeather(barcelona)

        val page = harness.open(RootMenuItem.WEATHER)

        assertTrue("expected a WeatherPageProvider, got $page", page is WeatherPageProvider)
    }

    /**
     * With nothing to read, the entry opens [MessageMenuPageProvider] instead of staying closed.
     */
    @Test
    fun selectOpensTheMessageMenuPageWhenThereIsNoUnreadMessage() = testScope.runTest {
        val page = Harness(backgroundScope).open(RootMenuItem.MESSAGE)
        assertTrue("expected MessageMenuPageProvider, got $page", page is MessageMenuPageProvider)
    }

    @Test
    fun selectOpensTheMessagePageOnceSomethingIsUnread() = testScope.runTest {
        val harness = Harness(backgroundScope)
        harness.arriveAndYield()

        val page = harness.open(RootMenuItem.MESSAGE)

        assertTrue("expected a MessagePageProvider, got $page", page is MessagePageProvider)
    }

    /**
     * A message that arrives on its own is announced, not read out unannounced: the interrupt
     * stack gets `MessagePromptPage`.
     */
    @Test
    fun anArrivingMessageMountsThePromptPage() = testScope.runTest {
        val harness = Harness(backgroundScope)
        harness.input.enterMode(DashboardMode.SAB_MODE)

        harness.message.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))

        assertTrue("the arriving message must claim the screen", harness.manager.stack.hasInterrupt)
        val page = harness.manager.stack.peek()
        assertTrue("expected a MessagePromptPageProvider, got $page", page is MessagePromptPageProvider)
    }

    /**
     * One message, one read page — the same rule the call already has. Two instances would give one
     * message two identities, and `Play` and the carousel would hold different pages for the same
     * thing.
     */
    @Test
    fun playAndTheCarouselShareOneMessagePage() = testScope.runTest {
        val harness = Harness(backgroundScope)
        harness.input.enterMode(DashboardMode.SAB_MODE)

        harness.message.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))
        harness.input.press(FunctionId.SELECT)
        val fromPlay = harness.manager.stack.peek()
        assertTrue("Play must open the reading, got $fromPlay", fromPlay is MessagePageProvider)

        // BACK on the read page: the screen comes back, the message stays unread.
        harness.message.yieldScreen()
        val fromCarousel = harness.open(RootMenuItem.MESSAGE)

        assertSame(
            "Play and the carousel must hold the same page instance",
            fromPlay,
            fromCarousel
        )
    }

    /**
     * "Do not take my screen" is not "there is no call": the carousel entry opens the very same
     * page, on the rider's own press.
     */
    @Test
    fun theCarouselEntryStillOpensTheCallPageWhenInterceptionIsOff() = testScope.runTest {
        val harness = Harness(backgroundScope)
        harness.settings.set { it.copy(interceptIncomingCalls = false) }
        harness.callDriver.post(callerName = "Ana", callerNumber = "+34600000000")

        val page = harness.open(RootMenuItem.CALL)

        assertTrue("expected a CallPageProvider, got $page", page is CallPageProvider)
    }

    /** A ringing call outranks an arriving message. */
    @Test
    fun aRingingCallTakesTheScreenFromAnUnreadMessage() = testScope.runTest {
        val harness = Harness(backgroundScope)
        harness.input.enterMode(DashboardMode.SAB_MODE)
        harness.message.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))

        harness.callDriver.post(callerName = "Ana", callerNumber = "+34600000000")

        assertTrue(
            "the call must hold the screen, got ${harness.manager.stack.peek()}",
            harness.manager.stack.peek() is CallPageProvider
        )
    }

    @Test
    fun theCarouselShowsTheSenderAndBodyOnTheMessageEntry() = testScope.runTest {
        val harness = Harness(backgroundScope)
        harness.arriveAndYield()

        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MESSAGE)) {
            harness.input.press(FunctionId.NEXT)
        }
        val root = harness.manager.stack.peek()!!
        val frame = root.render(1) as ProjectionFrame.MenuScreen

        assertEquals("Messages", frame.headerText)
        assertEquals("Elena", frame.contentTextLine1)
        assertEquals("Are you far?", frame.contentTextLine2)
    }

    @Test
    fun theCarouselShowsTheTemperatureOnTheWeatherEntry() = testScope.runTest {
        val harness = Harness(backgroundScope)
        harness.weather.updateWeather(barcelona)

        harness.input.enterMode(DashboardMode.SAB_MODE)
        val root = harness.manager.stack.peek()!!
        val frame = root.render(1) as ProjectionFrame.MenuScreen

        assertEquals("Weather", frame.headerText)
        assertEquals("24°C", frame.contentTextLine1)
        assertEquals(IconId.WEATHER_SUNNY, frame.contentIconId)
    }

    /**
     * One call, one page, reached both from the carousel and from the interrupt stack. A second
     * instance would give one call two identities and break the reference equality that
     * `RINGING -> ACTIVE` hangs on.
     */
    @Test
    fun theCarouselAndTheInterruptShareOneCallPage() = testScope.runTest {
        val harness = Harness(backgroundScope)
        harness.settings.set { it.copy(interceptIncomingCalls = false) }
        harness.callDriver.post(callerName = "Ana", callerNumber = "+34600000000")
        val fromCarousel = harness.open(RootMenuItem.CALL)

        harness.settings.set { it.copy(interceptIncomingCalls = true) }
        harness.callDriver.post(callerName = "Ana", callerNumber = "+34600000000")

        assertTrue("the ringing call must claim the screen", harness.manager.stack.hasInterrupt)
        assertSame(
            "the carousel and the interrupt stack must hold the same page instance",
            fromCarousel,
            harness.manager.stack.peek()
        )
    }

    /**
     * The cursor is walked before the call arrives on purpose. A ringing call claims the interrupt
     * stack, and from that moment the presses belong to the call page, not to the carousel.
     *
     * A call claiming the screen from HU_MODE leaves `stack.isEmpty` false, so the engine asks
     * `hasRoot` to decide whether entering SAB_MODE mounts a root; `InterruptBeforeHandoverE2eTest`
     * holds that ground.
     */
    @Test
    fun theCarouselShowsTheLiveNavigationAndCallSubtitles() = testScope.runTest {
        val harness = Harness(backgroundScope)
        harness.navigation.updateInstruction(
            NavInstruction(
                title = "Turn right",
                streetName = "Gran Via",
                distanceFormatted = "300 m",
                maneuver = ManeuverType.TURN_RIGHT
            )
        )

        harness.input.enterMode(DashboardMode.SAB_MODE)
        val root = harness.manager.stack.peek()!!

        harness.input.press(FunctionId.NEXT) // WEATHER -> NAVIGATION
        val navigationFrame = root.render(1) as ProjectionFrame.MenuScreen
        assertEquals("Navigation", navigationFrame.headerText)
        assertEquals("300 m", navigationFrame.contentTextLine1)

        harness.input.press(FunctionId.NEXT) // NAVIGATION -> CALL
        harness.callDriver.post(callerName = "Ana", callerNumber = "+34600000000")

        val callFrame = root.render(2) as ProjectionFrame.MenuScreen
        assertEquals("Phone", callFrame.headerText)
        assertEquals("Ana", callFrame.contentTextLine1)
        assertEquals("Ringing", callFrame.contentTextLine2)
    }
}
