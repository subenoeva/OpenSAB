package com.opensab.domain.speech

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MicrophoneGatedSpeechRecognizerTest {

    private val delegate = MockSpeechRecognizer()

    @Test
    fun aListenWithoutMicrophoneAccessFailsAsMicNeedsAppAndNeverOpensTheRecognizer() = runTest {
        val gated = MicrophoneGatedSpeechRecognizer(delegate) { false }
        delegate.willHear("hello")

        assertEquals(RecognitionFailure.MIC_NEEDS_APP, failureOf(gated))
        assertEquals(0, delegate.listenCalls)
    }

    @Test
    fun aListenWithMicrophoneAccessIsTheDelegatesOwn() = runTest {
        val gated = MicrophoneGatedSpeechRecognizer(delegate) { true }
        delegate.willHear("hello")

        assertEquals("hello", gated.listen(Locale.ENGLISH))
        assertEquals(Locale.ENGLISH, delegate.lastLocale)
    }

    @Test
    fun theAccessIsAskedAgainOnEveryListenBecauseTheUiCanPromoteTheServiceMidSession() = runTest {
        var granted = false
        val gated = MicrophoneGatedSpeechRecognizer(delegate) { granted }
        delegate.willHear("hello")

        assertEquals(RecognitionFailure.MIC_NEEDS_APP, failureOf(gated))
        granted = true

        assertEquals("hello", gated.listen())
        assertEquals(1, delegate.listenCalls)
    }

    private suspend fun failureOf(recognizer: SpeechRecognizer): RecognitionFailure? = try {
        recognizer.listen()
        null
    } catch (e: SpeechRecognitionException) {
        e.reason
    }
}
