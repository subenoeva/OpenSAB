package com.opensab.cluster

import com.opensab.domain.audio.AudioFocusKind
import com.opensab.domain.audio.RecordingAudioFocus
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

/**
 * The voice ducks the music while it speaks, and only while it speaks.
 *
 * In the field, with Tidal playing, the rider heard the forecast **underneath** the music: nothing
 * requested audio focus. At riding volume that is unintelligible.
 *
 * The owner is this class and not the synthesizer: the span of the focus is the span of the
 * script, and only whoever runs the script knows where that ends. `MAY_DUCK` and not `TRANSIENT`:
 * pausing a track for a nine-second forecast and resuming it after is worse than talking over a
 * quieter one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageVoiceAudioFocusTest {

    private val header = SpeechRequest(
        SpeechToken.WEATHER_FORECAST_MESSAGE,
        SpeechArg.Text("Vigo")
    )
    private val body = SpeechRequest(SpeechToken.MESSAGE_READ, SpeechArg.Text("on my way"))

    private val naming = SpeechTextResolver { request -> "said:${request.token}" }

    @Test
    fun ducksTheMusicWhileItSpeaksAndGivesTheFocusBackAfterwards() = runTest {
        val focus = RecordingAudioFocus()
        val synthesizer = MockSpeechSynthesizer()

        PageVoice(naming, synthesizer, focus, sessionScope = backgroundScope).say(listOf(header, body))
        advanceTimeBy(500.milliseconds)
        runCurrent()

        assertEquals(listOf(AudioFocusKind.TRANSIENT_MAY_DUCK), focus.requests)
        assertEquals(listOf(AudioFocusKind.TRANSIENT_MAY_DUCK), focus.abandoned)
        assertTrue("the voice kept the focus after it stopped speaking", focus.held.isEmpty())
    }

    /**
     * One focus for the whole script, not one per phrase. The forecast is six utterances
     * (`T7`), and taking and dropping the focus between each would make the music jump six times.
     */
    @Test
    fun asksForTheFocusOnceForTheWholeScript() = runTest {
        val focus = RecordingAudioFocus()

        PageVoice(naming, MockSpeechSynthesizer(), focus, sessionScope = backgroundScope).say(listOf(header, body, header))

        assertEquals(1, focus.requests.size)
    }

    /** The rider pressing BACK mid-forecast must not leave the music ducked for ever. */
    @Test
    fun givesTheFocusBackWhenTheVoiceIsCutMidScript() = runTest {
        val focus = RecordingAudioFocus()
        val synthesizer = MockSpeechSynthesizer().apply { speaksUntilStopped = true }
        val voice = PageVoice(naming, synthesizer, focus, sessionScope = backgroundScope)

        val job = launch { voice.say(listOf(header, body)) }
        runCurrent()
        assertEquals(listOf(AudioFocusKind.TRANSIENT_MAY_DUCK), focus.held)

        job.cancelAndJoin()
        advanceTimeBy(500.milliseconds)
        runCurrent()

        assertEquals(listOf(AudioFocusKind.TRANSIENT_MAY_DUCK), focus.abandoned)
        assertTrue("the music stayed ducked after the rider left", focus.held.isEmpty())
    }

    /**
     * A phone that will not yield the focus is not an error. The page still speaks — over the music
     * rather than above it, which is exactly what `T6` measured and is still better than silence.
     */
    @Test
    fun speaksAnywayWhenTheFocusIsDenied() = runTest {
        val focus = RecordingAudioFocus(granted = false)
        val synthesizer = MockSpeechSynthesizer()

        PageVoice(naming, synthesizer, focus, sessionScope = backgroundScope).say(listOf(header, body))

        assertEquals(
            listOf("said:WEATHER_FORECAST_MESSAGE", "said:MESSAGE_READ"),
            synthesizer.utterances.map { it.text }
        )
    }

    /** Nothing to say, nothing to duck. A page mounting with an empty script is silent. */
    @Test
    fun anEmptyScriptTakesNoFocus() = runTest {
        val focus = RecordingAudioFocus()

        PageVoice(naming, MockSpeechSynthesizer(), focus, sessionScope = backgroundScope).say(emptyList())

        assertTrue("the voice ducked the music with nothing to say", focus.requests.isEmpty())
    }
}
