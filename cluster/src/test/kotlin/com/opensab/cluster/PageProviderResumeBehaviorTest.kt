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
import com.opensab.cluster.nav.NavMenuPageProvider
import com.opensab.cluster.nav.NavVoiceSearchPageProvider
import com.opensab.cluster.nav.NavigationPageProvider
import com.opensab.cluster.rootmenu.RootMenuPageProvider
import com.opensab.cluster.text.englishClusterText
import com.opensab.cluster.text.englishWeatherCaptions
import com.opensab.cluster.weather.WeatherPageProvider
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.MockCallDialer
import com.opensab.domain.contacts.MockCallMenuAccess
import com.opensab.domain.contacts.MockContactDirectory
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.message.MockMessageSource
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.TrackMetadata
import com.opensab.domain.music.VolumeLevel
import com.opensab.domain.nav.FakeDeviceUnlockSource
import com.opensab.domain.nav.MockNavigationLauncher
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.speech.MockSpeechRecognizer
import com.opensab.domain.weather.WeatherInfo
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class PageProviderResumeBehaviorTest {

    @Test
    fun `all providers declare the correct resume behavior`() {
        val rootMenu = RootMenuPageProvider(
            entries = allRootMenuEntries(),
            pageFactory = { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) },
            copy = englishClusterText
        )
        assertEquals(ResumeBehavior.RETAIN, rootMenu.onResumeBehavior)

        val music = MusicPageProvider(MutableStateFlow(MusicState(TrackMetadata("", ""), PlaybackStatus.NONE)), englishClusterText, volumeControlOn)
        assertEquals(ResumeBehavior.RETAIN, music.onResumeBehavior)

        val volume = VolumePageProvider(MutableStateFlow(VolumeLevel(5, false)), englishClusterText, volumeControlOn)
        assertEquals(ResumeBehavior.RETAIN, volume.onResumeBehavior)

        val navigation = NavigationPageProvider(MutableStateFlow(null), englishClusterText)
        assertEquals(ResumeBehavior.RETAIN, navigation.onResumeBehavior)

        val call = CallPageProvider(MutableStateFlow(null), englishClusterText)
        assertEquals(ResumeBehavior.RETAIN, call.onResumeBehavior)

        val weather = WeatherPageProvider(MutableStateFlow(null), englishClusterText, englishWeatherCaptions)
        assertEquals(ResumeBehavior.RETAIN, weather.onResumeBehavior)

        val message = MessagePageProvider(MutableStateFlow(null), englishClusterText)
        assertEquals(ResumeBehavior.DISCARD, message.onResumeBehavior)

        /**
         * A dictation is not resumed, it is started again: the engine cancels the page's work on
         * the way out, so a retained transcript would come back with no way to send it.
         */
        val reply = MessageReplyPageProvider(
            recognizer = MockSpeechRecognizer(),
            messageSource = MockMessageSource(),
            sentLinger = MutableStateFlow(2.seconds),
            copy = englishClusterText
        )
        assertEquals(ResumeBehavior.DISCARD, reply.onResumeBehavior)

        val dial = CallDialPageProvider(
            seed = DialSeed.Voice,
            recognizer = MockSpeechRecognizer(),
            directory = MockContactDirectory(),
            dialer = MockCallDialer(),
            livePageFactory = { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) },
            copy = englishClusterText,
            activeCall = MutableStateFlow<CallInfo?>(null)
        )
        assertEquals(ResumeBehavior.DISCARD, dial.onResumeBehavior)

        val callMenu = CallMenuPageProvider(MockCallMenuAccess(), { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) }, englishClusterText)
        assertEquals(ResumeBehavior.RETAIN, callMenu.onResumeBehavior)

        val navMenu = NavMenuPageProvider({ StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) }, englishClusterText)
        assertEquals(ResumeBehavior.RETAIN, navMenu.onResumeBehavior)

        val navVoice = NavVoiceSearchPageProvider(
            recognizer = MockSpeechRecognizer(),
            launcher = MockNavigationLauncher(),
            unlockSource = FakeDeviceUnlockSource(),
            navInstructionFlow = MutableStateFlow(null),
            livePageFactory = { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) },
            copy = englishClusterText
        )
        assertEquals(ResumeBehavior.DISCARD, navVoice.onResumeBehavior)
    }
}
