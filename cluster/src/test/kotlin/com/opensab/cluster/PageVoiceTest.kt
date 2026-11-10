package com.opensab.cluster

import com.opensab.domain.audio.AudioFocus
import com.opensab.domain.audio.AudioFocusKind
import com.opensab.domain.audio.AudioFocusLease
import com.opensab.domain.speech.MockSpeechSynthesizer
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechTextResolver
import com.opensab.domain.speech.SpeechToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The single owner of the voice while a page is mounted, tested where it can be: the resolver is a
 * lambda and the synthesizer is the mock that lives next to its port, so what is checked is the
 * contract the three rows above this one will lean on — the page said this, in this order, and
 * stopped saying it when the rider left.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageVoiceTest {

    private val header = SpeechRequest(
        SpeechToken.WEATHER_FORECAST_MESSAGE,
        SpeechArg.Text("Vigo")
    )
    private val body = SpeechRequest(SpeechToken.MESSAGE_READ, SpeechArg.Text("on my way"))

    /** Names the token instead of a language, which is exactly what the real resolver hides. */
    private val naming = SpeechTextResolver { request -> "said:${request.token}" }

    @Test
    fun saysEveryRequestOfTheScriptInOrder() = runTest {
        val synthesizer = MockSpeechSynthesizer()

        PageVoice(naming, synthesizer, sessionScope = backgroundScope).say(listOf(header, body))

        assertEquals(
            listOf("said:WEATHER_FORECAST_MESSAGE", "said:MESSAGE_READ"),
            synthesizer.utterances.map { it.text }
        )
    }

    /**
     * A request without a locale is the system voice, which is every phrase but one: the rider
     * hears the forecast in his own language and nothing forces that.
     */
    @Test
    fun leavesARequestWithoutALocaleOnTheSystemVoice() = runTest {
        val synthesizer = MockSpeechSynthesizer()

        PageVoice(naming, synthesizer, sessionScope = backgroundScope).say(listOf(header))

        assertNull(synthesizer.utterances.single().locale)
    }

    /**
     * And a request that carries one imposes it: reading a Chinese message with a Spanish voice is
     * the case it is there for.
     */
    @Test
    fun handsTheForcedLocaleToTheSynthesizer() = runTest {
        val synthesizer = MockSpeechSynthesizer()
        val chinese = Locale.forLanguageTag("zh")

        PageVoice(naming, synthesizer, sessionScope = backgroundScope).say(
            listOf(
                SpeechRequest(
                    SpeechToken.MESSAGE_READ,
                    listOf(SpeechArg.Text("你好")),
                    locale = chinese
                )
            )
        )

        assertEquals(chinese, synthesizer.utterances.single().locale)
    }

    /** One script, two voices: the locale travels per phrase and not per page. */
    @Test
    fun keepsOneLocalePerPhraseWithinTheSameScript() = runTest {
        val synthesizer = MockSpeechSynthesizer()
        val chinese = Locale.forLanguageTag("zh")

        PageVoice(naming, synthesizer, sessionScope = backgroundScope).say(
            listOf(
                header,
                SpeechRequest(
                    SpeechToken.MESSAGE_READ,
                    listOf(SpeechArg.Text("你好")),
                    locale = chinese
                )
            )
        )

        assertEquals(listOf(null, chinese), synthesizer.utterances.map { it.locale })
    }

    /**
     * The words are the resolver's business and the schedule is this class's. Speaking the token
     * itself, or the arguments raw, is the defect this pins down.
     */
    @Test
    fun saysWhatTheResolverReturnedAndNothingElse() = runTest {
        val synthesizer = MockSpeechSynthesizer()
        val resolver = SpeechTextResolver { "El tiempo en Vigo" }

        PageVoice(resolver, synthesizer, sessionScope = backgroundScope).say(listOf(header))

        assertEquals("El tiempo en Vigo", synthesizer.utterances.single().text)
    }

    /**
     * The locale stays null all the way down, which is the system language: the voice-language
     * preference does not exist yet and no caller has one to pass.
     */
    @Test
    fun speaksInTheSystemLanguage() = runTest {
        val synthesizer = MockSpeechSynthesizer()

        PageVoice(naming, synthesizer, sessionScope = backgroundScope).say(listOf(header))

        assertEquals(null, synthesizer.utterances.single().locale)
    }

    @Test
    fun anEmptyScriptSaysNothing() = runTest {
        val synthesizer = MockSpeechSynthesizer()

        PageVoice(naming, synthesizer, sessionScope = backgroundScope).say(emptyList())

        assertTrue(synthesizer.utterances.isEmpty())
    }

    /**
     * The rider pressing BACK halfway through. The second phrase is never said and the voice is
     * cut, rather than the page being left talking about a screen that is gone.
     */
    @Test
    fun cancellingTheJobCutsTheVoiceMidScript() = runTest {
        val synthesizer = MockSpeechSynthesizer().apply { speaksUntilStopped = true }
        val voice = PageVoice(naming, synthesizer, sessionScope = backgroundScope)

        val job = launch { voice.say(listOf(header, body)) }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(listOf("said:WEATHER_FORECAST_MESSAGE"), synthesizer.utterances.map { it.text })
        assertEquals(1, synthesizer.stopCalls)
    }

    /**
     * Cancelling is a normal outcome, and a normal outcome still has to reach the job that launched
     * it: one stray handler around the suspension turns "the rider left" into a swallowed failure,
     * and everything above would keep speaking.
     */
    @Test
    fun cancellingIsNotSwallowed() = runTest {
        val synthesizer = MockSpeechSynthesizer().apply { speaksUntilStopped = true }
        val voice = PageVoice(naming, synthesizer, sessionScope = backgroundScope)
        var reached: Throwable? = null

        val job = launch {
            try {
                voice.say(listOf(header, body))
            } catch (e: CancellationException) {
                reached = e
                throw e
            }
        }
        runCurrent()
        job.cancelAndJoin()

        assertTrue("The CancellationException never reached the caller", reached != null)
    }

    private class MockAudioFocus : AudioFocus {
        var requestCount = 0
        var abandonCount = 0
        var shouldGrant = true

        override fun request(kind: AudioFocusKind): AudioFocusLease? {
            if (!shouldGrant) return null
            requestCount++
            return AudioFocusLease { abandonCount++ }
        }
    }

    @Test
    fun consecutiveSayCallsRetainSingleLeaseWithin500Ms() = runTest {
        val audioFocus = MockAudioFocus()
        val synthesizer = MockSpeechSynthesizer()
        val voice = PageVoice(
            resolver = naming,
            synthesizer = synthesizer,
            audioFocus = audioFocus,
            sessionScope = backgroundScope,
            releaseDebounce = 500.milliseconds
        )

        voice.say(listOf(header))
        assertEquals(1, audioFocus.requestCount)
        assertEquals(0, audioFocus.abandonCount)

        advanceTimeBy(300.milliseconds)
        voice.say(listOf(body))
        assertEquals(1, audioFocus.requestCount)
        assertEquals(0, audioFocus.abandonCount)

        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertEquals(1, audioFocus.abandonCount)
    }

    @Test
    fun inCallSuppressesSpeechAndReleasesAnyPendingFocus() = runTest {
        val audioFocus = MockAudioFocus()
        val synthesizer = MockSpeechSynthesizer()
        var inCallState = false
        val voice = PageVoice(
            resolver = naming,
            synthesizer = synthesizer,
            audioFocus = audioFocus,
            sessionScope = backgroundScope,
            inCall = { inCallState }
        )

        voice.say(listOf(header))
        assertEquals(1, audioFocus.requestCount)

        inCallState = true
        voice.say(listOf(body))
        // Does not speak or request new focus, and pending lease is abandoned
        assertEquals(1, audioFocus.abandonCount)
        assertEquals(1, synthesizer.utterances.size)
    }

    @Test
    fun theNextSessionSpeaksAgainAfterStop() = runTest {
        val audioFocus = MockAudioFocus()
        val synthesizer = MockSpeechSynthesizer()
        val voice = PageVoice(
            resolver = naming,
            synthesizer = synthesizer,
            audioFocus = audioFocus,
            sessionScope = backgroundScope
        )

        voice.say(listOf(header))
        voice.stop()
        voice.say(listOf(body))

        assertEquals(listOf("said:WEATHER_FORECAST_MESSAGE", "said:MESSAGE_READ"), synthesizer.utterances.map { it.text })
        assertEquals(2, audioFocus.requestCount)

        advanceTimeBy(501.milliseconds)
        assertEquals("the second session's lease was never given back", 2, audioFocus.abandonCount)
    }

    @Test
    fun stopAbandonsLeaseImmediatelyAndPreventsFurtherDebounce() = runTest {
        val audioFocus = MockAudioFocus()
        val synthesizer = MockSpeechSynthesizer()
        val voice = PageVoice(
            resolver = naming,
            synthesizer = synthesizer,
            audioFocus = audioFocus,
            sessionScope = backgroundScope,
            releaseDebounce = 500.milliseconds
        )

        voice.say(listOf(header))
        assertEquals(0, audioFocus.abandonCount)

        voice.stop()
        assertEquals(1, audioFocus.abandonCount)

        advanceTimeBy(1.seconds)
        // No redundant abandon
        assertEquals(1, audioFocus.abandonCount)
    }
}
