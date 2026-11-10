package com.opensab.cluster

import com.opensab.cluster.call.CallDialPageProvider
import com.opensab.cluster.call.CallMenuPageProvider
import com.opensab.cluster.call.CallPageProvider
import com.opensab.cluster.call.DialSeed
import com.opensab.cluster.message.MessagePageProvider
import com.opensab.cluster.message.MessageReplyPageProvider
import com.opensab.cluster.music.MusicPageProvider
import com.opensab.cluster.music.VolumePageProvider
import com.opensab.cluster.music.volumeControlOn
import com.opensab.cluster.nav.NavVoiceSearchPageProvider
import com.opensab.cluster.nav.NavigationPageProvider
import com.opensab.cluster.rootmenu.RootMenuPageProvider
import com.opensab.cluster.text.englishClusterText
import com.opensab.cluster.text.englishWeatherCaptions
import com.opensab.cluster.weather.WeatherPageProvider
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.MockCallDialer
import com.opensab.domain.contacts.CallMenuGates
import com.opensab.domain.contacts.Contact
import com.opensab.domain.contacts.MockCallMenuAccess
import com.opensab.domain.contacts.MockContactDirectory
import com.opensab.domain.contacts.PhoneKind
import com.opensab.domain.contacts.PhoneNumber
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.message.MockMessageSource
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.TrackMetadata
import com.opensab.domain.music.VolumeLevel
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.nav.FakeDeviceUnlockSource
import com.opensab.domain.nav.MockNavigationLauncher
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.speech.MockSpeechRecognizer
import com.opensab.domain.weather.WeatherInfo
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PageProviderInvalidationContractTest {

    private class Scenario(
        val name: String,
        val provider: PageProvider,
        val tickSource: suspend () -> Unit
    )

    private fun scenarios(): List<Scenario> {
        val rootMusic = MutableStateFlow(MusicState(TrackMetadata("", ""), PlaybackStatus.NONE))
        val rootNav = MutableStateFlow<NavInstruction?>(null)
        val rootCall = MutableStateFlow<CallInfo?>(null)
        val rootWeather = MutableStateFlow<WeatherInfo?>(null)
        val rootMessage = MutableStateFlow<MessageInfo?>(null)

        val musicState = MutableStateFlow(MusicState(TrackMetadata("", ""), PlaybackStatus.NONE))
        val volumeLevel = MutableStateFlow(VolumeLevel(5, false))
        val navState = MutableStateFlow<NavInstruction?>(null)
        val callState = MutableStateFlow<CallInfo?>(null)
        val weatherState = MutableStateFlow<WeatherInfo?>(null)
        val messageState = MutableStateFlow<MessageInfo?>(null)

        return listOf(
            Scenario(
                "RootMenu",
                RootMenuPageProvider(
                    entries = allRootMenuEntries(),
                    musicState = rootMusic,
                    navInstruction = rootNav,
                    activeCall = rootCall,
                    currentWeather = rootWeather,
                    currentMessage = rootMessage,
                    pageFactory = { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) },
                    copy = englishClusterText
                )
            ) { rootMusic.value = rootMusic.value.copy(status = PlaybackStatus.PLAYING) },
            Scenario(
                "Music",
                MusicPageProvider(musicState, englishClusterText, volumeControlOn)
            ) { musicState.value = musicState.value.copy(status = PlaybackStatus.PLAYING) },
            Scenario(
                "Volume",
                VolumePageProvider(volumeLevel, englishClusterText, volumeControlOn)
            ) { volumeLevel.value = volumeLevel.value.copy(percent = 10) },
            Scenario(
                "Navigation",
                NavigationPageProvider(navState, englishClusterText)
            ) { navState.value = NavInstruction("", "", "", com.opensab.domain.nav.ManeuverType.STRAIGHT) },
            Scenario(
                "Call",
                CallPageProvider(callState, englishClusterText)
            ) { callState.value = CallInfo("123", "John", com.opensab.domain.call.CallStatus.RINGING) },
            Scenario(
                "Weather",
                WeatherPageProvider(weatherState, englishClusterText, englishWeatherCaptions)
            ) {
                weatherState.value = WeatherInfo(
                    locationName = "Valencia",
                    temperature = 28,
                    temperatureUnit = "C",
                    condition = "Sunny",
                    type = com.opensab.domain.weather.WeatherConditionType.SUNNY
                )
            },
            Scenario(
                "Message",
                MessagePageProvider(messageState, englishClusterText)
            ) { messageState.value = MessageInfo("John", "Hello") }
        )
    }

    @Test
    fun `mounting a page emits no redraw`() = runTest {
        scenarios().forEach { scenario ->
            val collected = mutableListOf<Unit>()
            val job = launch {
                scenario.provider.invalidations.toList(collected)
            }
            delay(50.milliseconds) // Let the collector run
            job.cancel()
            assertTrue("${scenario.name} should not tick on mount", collected.isEmpty())
        }
    }

    /**
     * The reply page keeps the same contract off a source of its own making, so it gets a scenario
     * of its own rather than a bent one.
     *
     * Its redraw source is its own state: seven pages would each tick on being mounted,
     * this one ticks on a transition. What is pinned is unchanged — nothing on a mount, exactly one
     * per change.
     */
    @Test
    fun `the reply page ticks on a transition and never on a mount`() = runTest {
        val recognizer = MockSpeechRecognizer().apply { listensUntilReleased = true }
        val page = MessageReplyPageProvider(
            recognizer = recognizer,
            messageSource = MockMessageSource(),
            sentLinger = MutableStateFlow(2.seconds),
            copy = englishClusterText
        )
        val collected = mutableListOf<Unit>()
        val redraws = launch { page.invalidations.toList(collected) }
        val work = launch { page.work() }
        delay(50.milliseconds)

        assertTrue("mounting and listening are not a transition", collected.isEmpty())

        recognizer.release("On my way")
        delay(50.milliseconds)
        redraws.cancel()
        work.cancel()

        assertEquals("being understood is exactly one transition", 1, collected.size)
    }

    @Test
    fun `the dialler page ticks on a transition and never on a mount`() = runTest {
        val recognizer = MockSpeechRecognizer().apply { listensUntilReleased = true }
        val directory = MockContactDirectory().apply {
            results = listOf(
                Contact(
                    1, "Ana Ruiz", false,
                    listOf(PhoneNumber("600100100", PhoneKind.MOBILE))
                )
            )
        }
        val page = CallDialPageProvider(
            seed = DialSeed.Voice,
            recognizer = recognizer,
            directory = directory,
            dialer = MockCallDialer(),
            livePageFactory = { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) },
            copy = englishClusterText,
            activeCall = MutableStateFlow<CallInfo?>(null)
        )
        val collected = mutableListOf<Unit>()
        val redraws = launch { page.invalidations.toList(collected) }
        val work = launch { page.work() }
        delay(50.milliseconds)

        assertTrue("mounting and listening are not a transition", collected.isEmpty())

        recognizer.release("llama a Ana")
        delay(50.milliseconds)
        redraws.cancel()
        work.cancel()

        assertEquals("being understood is exactly one transition", 1, collected.size)
    }

    @Test
    fun `the call menu ticks when the gates arrive and never on a mount`() = runTest {
        val access = MockCallMenuAccess(CallMenuGates(voice = true, favorites = emptyList()))
        val page = CallMenuPageProvider(access, { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) }, englishClusterText)
        val collected = mutableListOf<Unit>()
        val redraws = launch { page.invalidations.toList(collected) }
        delay(50.milliseconds)

        assertTrue("mounting is not a transition", collected.isEmpty())

        val work = launch { page.work() }
        delay(50.milliseconds)
        redraws.cancel()
        work.cancel()

        assertEquals("gates arriving is exactly one transition", 1, collected.size)
    }

    @Test
    fun `the nav voice search page ticks on a transition and never on a mount`() = runTest {
        val recognizer = MockSpeechRecognizer().apply { listensUntilReleased = true }
        val page = NavVoiceSearchPageProvider(
            recognizer = recognizer,
            launcher = MockNavigationLauncher(),
            unlockSource = FakeDeviceUnlockSource(),
            navInstructionFlow = MutableStateFlow(null),
            livePageFactory = { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) },
            copy = englishClusterText
        )
        val collected = mutableListOf<Unit>()
        val redraws = launch { page.invalidations.toList(collected) }
        val work = launch { page.work() }
        delay(50.milliseconds)

        assertTrue("mounting and listening are not a transition", collected.isEmpty())

        recognizer.release("Gasolinera")
        delay(50.milliseconds)
        redraws.cancel()
        work.cancel()

        assertEquals("being understood is exactly one transition", 1, collected.size)
    }

    @Test
    fun `moving a source emits exactly one redraw`() = runTest {
        scenarios().forEach { scenario ->
            val collected = mutableListOf<Unit>()
            val job = launch {
                scenario.provider.invalidations.toList(collected)
            }
            delay(50.milliseconds)
            scenario.tickSource()
            delay(50.milliseconds)
            job.cancel()
            assertEquals("${scenario.name} should tick exactly once on source change", 1, collected.size)
        }
    }

    @Test
    fun `a press that only touches a source redraws through the source, not through onInput`() = runTest {
        val volumeLevel = MutableStateFlow(VolumeLevel(5, false))
        val volumeSource = object : VolumeSource {
            override val level = volumeLevel
            override fun raise() { volumeLevel.value = volumeLevel.value.copy(percent = 6) }
            override fun lower() {}
            override fun toggleMute() {}
            override fun start() {}
            override fun stop() {}
        }
        val provider = VolumePageProvider(volumeLevel, englishClusterText, volumeControlOn, volumeSource)
        
        val collected = mutableListOf<Unit>()
        val job = launch {
            provider.invalidations.toList(collected)
        }
        delay(50.milliseconds)
        
        val action = provider.onInput(FunctionId.VOLUME_UP)
        assertEquals(NavigationAction.None, action)
        
        delay(50.milliseconds)
        job.cancel()
        assertEquals("Volume should tick exactly once through source change", 1, collected.size)
    }
}
