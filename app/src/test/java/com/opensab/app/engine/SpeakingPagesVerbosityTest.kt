package com.opensab.app.engine

import com.opensab.app.source.speech.phrases
import com.opensab.cluster.LiveScreenPresets
import com.opensab.cluster.PageVoice
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.StubPageProvider
import com.opensab.cluster.call.CallDialPageProvider
import com.opensab.cluster.call.CallPageProvider
import com.opensab.cluster.call.DialSeed
import com.opensab.cluster.message.MessagePageProvider
import com.opensab.cluster.message.MessagePromptPageProvider
import com.opensab.cluster.message.MessageReplyPageProvider
import com.opensab.cluster.text.englishClusterText
import com.opensab.cluster.text.englishWeatherCaptions
import com.opensab.cluster.weather.WeatherPageProvider
import com.opensab.domain.audio.AudioFocusKind
import com.opensab.domain.audio.Cue
import com.opensab.domain.audio.MockCuePlayer
import com.opensab.domain.audio.RecordingAudioFocus
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.CallStatus
import com.opensab.domain.call.MockCallDialer
import com.opensab.domain.contacts.CallTarget
import com.opensab.domain.contacts.MockContactDirectory
import com.opensab.domain.contacts.PhoneKind
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.message.MockMessageSource
import com.opensab.domain.settings.VoiceVerbosity
import com.opensab.domain.speech.MockSpeechRecognizer
import com.opensab.domain.speech.MockSpeechSynthesizer
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechTextResolver
import com.opensab.domain.speech.assembleSpeechText
import com.opensab.domain.weather.MockWeatherSource
import com.opensab.domain.weather.WeatherInfo
import com.opensab.sab.protocol.assignment.FunctionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * All six speaking pages honor the three voice verbosity modes (NONE, STANDARD, ADVANCED), and
 * sound cues (earcons) operate independently from voice guidance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeakingPagesVerbosityTest {

    private class VerbosityTestResolver : SpeechTextResolver {
        override fun resolve(request: SpeechRequest): String =
            resolve(request, VoiceVerbosity.STANDARD)

        override fun resolve(request: SpeechRequest, verbosity: VoiceVerbosity): String {
            if (verbosity == VoiceVerbosity.NONE) return ""
            val token = request.token
            val phrases = token.phrases()
            val essential = "essential:${token.name}"
            if (verbosity == VoiceVerbosity.STANDARD || phrases.help == null) {
                return essential
            }
            val help = "help:${token.name}"
            return assembleSpeechText(essential, "... ", help)
        }
    }

    private data class PageHarness(
        val page: SpeakingPage,
        val trigger: suspend () -> Unit
    )

    private fun createHarnesses(scope: CoroutineScope): List<Pair<String, PageHarness>> {
        val weatherSource = MockWeatherSource()
        val weatherPage = WeatherPageProvider(
            currentWeather = weatherSource.currentWeather,
            copy = englishClusterText,
            captions = englishWeatherCaptions,
            weatherSource = weatherSource
        )
        val weatherHarness = PageHarness(weatherPage) {
            weatherSource.updateWeather(
                WeatherInfo(
                    locationName = "Vigo",
                    temperature = 20
                )
            )
        }

        val promptFlow = MutableStateFlow<MessageInfo?>(null)
        val promptPage = MessagePromptPageProvider(
            currentMessage = promptFlow,
            copy = englishClusterText,
            readPageFactory = { StubPageProvider("StubRead", LiveScreenPresets.idle(1)) }
        )
        val promptHarness = PageHarness(promptPage) {
            promptFlow.value = MessageInfo(
                sender = "Alice",
                body = "Hello",
                appName = "WhatsApp"
            )
        }

        val messageFlow = MutableStateFlow<MessageInfo?>(null)
        val messagePage = MessagePageProvider(
            currentMessage = messageFlow,
            copy = englishClusterText
        )
        val messageHarness = PageHarness(messagePage) {
            messageFlow.value = MessageInfo(
                sender = "Alice",
                body = "Hello there",
                appName = "WhatsApp"
            )
        }

        val callFlow = MutableStateFlow<CallInfo?>(null)
        val callPage = CallPageProvider(
            activeCall = callFlow,
            copy = englishClusterText
        )
        val callHarness = PageHarness(callPage) {
            callFlow.value = CallInfo(
                callerName = "Bob",
                callerNumber = "+123456",
                status = CallStatus.RINGING
            )
        }

        val dialTargets = listOf(CallTarget("Carlos", "123", PhoneKind.MOBILE))
        val dialPage = CallDialPageProvider(
            seed = DialSeed.Favorites(dialTargets),
            recognizer = MockSpeechRecognizer(),
            directory = MockContactDirectory(),
            dialer = MockCallDialer(),
            livePageFactory = { StubPageProvider("StubCall", LiveScreenPresets.idle(1)) },
            copy = englishClusterText,
            activeCall = MutableStateFlow<CallInfo?>(null)
        )
        val dialHarness = PageHarness(dialPage) {
            // Already emits on mount/collection because DialSeed.Favorites starts in Confirming
        }

        val replyRecognizer = MockSpeechRecognizer()
        replyRecognizer.willHear("Dictated message")
        val replyPage = MessageReplyPageProvider(
            recognizer = replyRecognizer,
            messageSource = MockMessageSource(),
            sentLinger = MutableStateFlow(2.seconds),
            copy = englishClusterText,
            quickReplies = MutableStateFlow(listOf("Yes", "No"))
        )
        val replyHarness = PageHarness(replyPage) {
            scope.launch { replyPage.work() }
        }

        return listOf(
            "WeatherPageProvider" to weatherHarness,
            "MessagePromptPageProvider" to promptHarness,
            "MessagePageProvider" to messageHarness,
            "CallPageProvider" to callHarness,
            "CallDialPageProvider" to dialHarness,
            "MessageReplyPageProvider" to replyHarness
        )
    }

    @Test
    fun allSpeakingPagesAreSilencedInNoneMode() = runTest {
        val harnesses = createHarnesses(backgroundScope)

        for ((name, harness) in harnesses) {
            val focus = RecordingAudioFocus()
            val synthesizer = MockSpeechSynthesizer()
            val voice = PageVoice(
                resolver = VerbosityTestResolver(),
                synthesizer = synthesizer,
                audioFocus = focus,
                verbosity = { VoiceVerbosity.NONE },
                sessionScope = backgroundScope
            )

            val collector = backgroundScope.launch {
                harness.page.speech.collect { voice.say(it) }
            }
            harness.trigger()
            runCurrent()

            assertTrue(
                "$name must not produce any speech in NONE mode",
                synthesizer.utterances.isEmpty()
            )
            assertTrue(
                "$name must not request audio focus in NONE mode",
                focus.requests.isEmpty()
            )
            collector.cancel()
        }
    }

    @Test
    fun allSpeakingPagesEmitEssentialOnlyInStandardMode() = runTest {
        val harnesses = createHarnesses(backgroundScope)

        for ((name, harness) in harnesses) {
            val focus = RecordingAudioFocus()
            val synthesizer = MockSpeechSynthesizer()
            val voice = PageVoice(
                resolver = VerbosityTestResolver(),
                synthesizer = synthesizer,
                audioFocus = focus,
                verbosity = { VoiceVerbosity.STANDARD },
                sessionScope = backgroundScope
            )

            val collector = backgroundScope.launch {
                harness.page.speech.collect { voice.say(it) }
            }
            harness.trigger()
            runCurrent()

            assertTrue(
                "$name must produce speech in STANDARD mode",
                synthesizer.utterances.isNotEmpty()
            )
            assertEquals(
                "$name must request audio focus in STANDARD mode",
                listOf(AudioFocusKind.TRANSIENT_MAY_DUCK),
                focus.requests
            )

            val text = synthesizer.utterances.first().text
            assertTrue(
                "$name utterance must start with essential text: '$text'",
                text.startsWith("essential:")
            )
            assertTrue(
                "$name utterance must NOT contain help text in STANDARD mode: '$text'",
                !text.contains("help:")
            )
            collector.cancel()
        }
    }

    @Test
    fun allSpeakingPagesEmitEssentialPlusHelpInAdvancedMode() = runTest {
        val harnesses = createHarnesses(backgroundScope)

        for ((name, harness) in harnesses) {
            val focus = RecordingAudioFocus()
            val synthesizer = MockSpeechSynthesizer()
            val voice = PageVoice(
                resolver = VerbosityTestResolver(),
                synthesizer = synthesizer,
                audioFocus = focus,
                verbosity = { VoiceVerbosity.ADVANCED },
                sessionScope = backgroundScope
            )

            val collector = backgroundScope.launch {
                harness.page.speech.collect { voice.say(it) }
            }
            harness.trigger()
            runCurrent()

            assertTrue(
                "$name must produce speech in ADVANCED mode",
                synthesizer.utterances.isNotEmpty()
            )
            assertEquals(
                "$name must request audio focus in ADVANCED mode",
                listOf(AudioFocusKind.TRANSIENT_MAY_DUCK),
                focus.requests
            )

            val text = synthesizer.utterances.first().text
            assertTrue(
                "$name utterance must contain essential text: '$text'",
                text.contains("essential:")
            )
            assertTrue(
                "$name utterance must contain help text in ADVANCED mode: '$text'",
                text.contains("help:")
            )
            collector.cancel()
        }
    }

    @Test
    fun soundCuesPlayIndependentlyOfVoiceVerbosity() = runTest {
        // CallDialPageProvider is both a CuingPage and a SpeakingPage.
        for (verbosity in VoiceVerbosity.entries) {
            val focus = RecordingAudioFocus()
            val synthesizer = MockSpeechSynthesizer()
            val cuePlayer = MockCuePlayer()
            val voice = PageVoice(
                resolver = VerbosityTestResolver(),
                synthesizer = synthesizer,
                audioFocus = focus,
                verbosity = { verbosity },
                sessionScope = backgroundScope
            )

            val targets = listOf(
                CallTarget("Alice", "111", PhoneKind.MOBILE),
                CallTarget("Bob", "222", PhoneKind.MOBILE)
            )
            val dialPage = CallDialPageProvider(
                seed = DialSeed.Favorites(targets),
                recognizer = MockSpeechRecognizer(),
                directory = MockContactDirectory(),
                dialer = MockCallDialer(),
                livePageFactory = { StubPageProvider("StubCall", LiveScreenPresets.idle(1)) },
                copy = englishClusterText,
                activeCall = MutableStateFlow<CallInfo?>(null)
            )

            val speechJob = backgroundScope.launch {
                dialPage.speech.collect { voice.say(it) }
            }
            val cueJob = backgroundScope.launch {
                dialPage.cues.collect { cuePlayer.play(it) }
            }
            runCurrent()

            // When moving selection, CuingPage emits Cue.MOVE
            dialPage.onInput(FunctionId.NEXT)
            runCurrent()

            assertEquals(
                "Cue.MOVE must be played in verbosity $verbosity",
                listOf(Cue.MOVE),
                cuePlayer.played
            )

            if (verbosity == VoiceVerbosity.NONE) {
                assertTrue("Zero speech in NONE mode", synthesizer.utterances.isEmpty())
                assertTrue("Zero focus in NONE mode", focus.requests.isEmpty())
            } else {
                assertTrue("Speech emitted in $verbosity mode", synthesizer.utterances.isNotEmpty())
            }

            speechJob.cancel()
            cueJob.cancel()
        }
    }
}
