package com.opensab.cluster

import com.opensab.domain.audio.AudioFocusKind
import com.opensab.domain.audio.RecordingAudioFocus
import com.opensab.domain.settings.VoiceVerbosity
import com.opensab.domain.speech.MockSpeechSynthesizer
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechTextResolver
import com.opensab.domain.speech.SpeechToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class PageVoiceVerbosityTest {

    private val header = SpeechRequest(
        SpeechToken.WEATHER_FORECAST_MESSAGE,
        SpeechArg.Text("Vigo")
    )

    private class RecordingVerbosityResolver : SpeechTextResolver {
        val resolvedModes = mutableListOf<VoiceVerbosity>()

        override fun resolve(request: SpeechRequest): String =
            resolve(request, VoiceVerbosity.STANDARD)

        override fun resolve(request: SpeechRequest, verbosity: VoiceVerbosity): String {
            resolvedModes.add(verbosity)
            return when (verbosity) {
                VoiceVerbosity.NONE -> ""
                VoiceVerbosity.STANDARD -> "The weather in Vigo"
                VoiceVerbosity.ADVANCED -> "The weather in Vigo. Press Select for forecast."
            }
        }
    }

    @Test
    fun inNoneModeZeroCallsToSynthesizerAndAudioFocus() = runTest {
        val focus = RecordingAudioFocus()
        val synthesizer = MockSpeechSynthesizer()
        val resolver = RecordingVerbosityResolver()
        val voice = PageVoice(
            resolver = resolver,
            synthesizer = synthesizer,
            audioFocus = focus,
            verbosity = { VoiceVerbosity.NONE },
            sessionScope = backgroundScope
        )

        voice.say(listOf(header))

        assertTrue("synthesizer must not be called in NONE mode", synthesizer.utterances.isEmpty())
        assertTrue("audio focus must not be requested in NONE mode", focus.requests.isEmpty())
        assertTrue("resolver must not be called in NONE mode", resolver.resolvedModes.isEmpty())
    }

    @Test
    fun inStandardModeSpeaksEssentialTextOnly() = runTest {
        val focus = RecordingAudioFocus()
        val synthesizer = MockSpeechSynthesizer()
        val resolver = RecordingVerbosityResolver()
        val voice = PageVoice(
            resolver = resolver,
            synthesizer = synthesizer,
            audioFocus = focus,
            verbosity = { VoiceVerbosity.STANDARD },
            sessionScope = backgroundScope
        )

        voice.say(listOf(header))

        assertEquals(listOf(AudioFocusKind.TRANSIENT_MAY_DUCK), focus.requests)
        assertEquals(listOf("The weather in Vigo"), synthesizer.utterances.map { it.text })
        assertEquals(listOf(VoiceVerbosity.STANDARD), resolver.resolvedModes)
    }

    @Test
    fun inAdvancedModeSpeaksEssentialPlusHelpText() = runTest {
        val focus = RecordingAudioFocus()
        val synthesizer = MockSpeechSynthesizer()
        val resolver = RecordingVerbosityResolver()
        val voice = PageVoice(
            resolver = resolver,
            synthesizer = synthesizer,
            audioFocus = focus,
            verbosity = { VoiceVerbosity.ADVANCED },
            sessionScope = backgroundScope
        )

        voice.say(listOf(header))

        assertEquals(listOf(AudioFocusKind.TRANSIENT_MAY_DUCK), focus.requests)
        assertEquals(
            listOf("The weather in Vigo. Press Select for forecast."),
            synthesizer.utterances.map { it.text }
        )
        assertEquals(listOf(VoiceVerbosity.ADVANCED), resolver.resolvedModes)
    }

    @Test
    fun changingVerbosityBetweenCallsTakesImmediateEffect() = runTest {
        val focus = RecordingAudioFocus()
        val synthesizer = MockSpeechSynthesizer()
        val resolver = RecordingVerbosityResolver()
        var currentMode = VoiceVerbosity.NONE
        val voice = PageVoice(
            resolver = resolver,
            synthesizer = synthesizer,
            audioFocus = focus,
            verbosity = { currentMode },
            sessionScope = backgroundScope
        )

        voice.say(listOf(header))
        assertEquals(0, synthesizer.utterances.size)

        currentMode = VoiceVerbosity.ADVANCED
        voice.say(listOf(header))
        assertEquals(1, synthesizer.utterances.size)
        assertEquals(
            "The weather in Vigo. Press Select for forecast.",
            synthesizer.utterances.single().text
        )
    }

    @Test
    fun cleanCancellationInAdvancedModeReleasesAudioFocus() = runTest {
        val focus = RecordingAudioFocus()
        val synthesizer = MockSpeechSynthesizer().apply { speaksUntilStopped = true }
        val resolver = RecordingVerbosityResolver()
        val voice = PageVoice(
            resolver = resolver,
            synthesizer = synthesizer,
            audioFocus = focus,
            verbosity = { VoiceVerbosity.ADVANCED },
            sessionScope = backgroundScope
        )

        val job = launch { voice.say(listOf(header)) }
        runCurrent()
        assertEquals(listOf(AudioFocusKind.TRANSIENT_MAY_DUCK), focus.held)

        job.cancelAndJoin()
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertTrue("Focus lease must be abandoned on cancellation", focus.held.isEmpty())
        assertEquals(listOf(AudioFocusKind.TRANSIENT_MAY_DUCK), focus.abandoned)
    }
}
