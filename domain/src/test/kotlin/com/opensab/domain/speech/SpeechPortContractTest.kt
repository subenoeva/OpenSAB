package com.opensab.domain.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract of the two speech ports, and deliberately **not** `TextToSpeech`.
 *
 * These four run against the mocks and none of them touches the framework: the two rules worth
 * pinning — cancelling an utterance is a normal outcome, and not understanding is a normal
 * outcome — are promises of the port, not of the engine underneath it. What is written here is the
 * whole of what the consumers stacked above this layer are allowed to assume.
 *
 * That is the deliberate half. `AndroidSpeechSynthesizer` and `AndroidSpeechRecognizer` carry no
 * JVM test at all, by the seam rule that also leaves `AndroidGattTransport` and
 * `AndroidLocationSource` untested: below the interface lives the framework, and a test there would
 * only ever be a test of a mock of Android. Their evidence comes from the phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeechPortContractTest {

    /**
     * The whole rule in one line: what reaches the caller is the coroutine's own cancellation and
     * not an exception this layer invented. The cause is read off the job rather than
     * intercepted, because intercepting a cancelled suspension is the very defect to avoid.
     */
    @Test
    fun cancellingAnUtteranceIsNotAFailure() = runTest {
        val synthesizer = MockSpeechSynthesizer().apply { speaksUntilStopped = true }

        val utterance = launch { synthesizer.speak("The weather in Vigo") }
        var completionCause: Throwable? = null
        utterance.invokeOnCompletion { cause -> completionCause = cause }
        runCurrent() // the utterance is now suspended mid-sentence, which is where BACK arrives

        utterance.cancelAndJoin()

        assertTrue(
            "cancelling a running utterance ends it as a cancellation and not as a domain " +
                "failure, was $completionCause",
            completionCause is CancellationException
        )
    }

    /** Cancelling the coroutine has to reach the engine, the way `invokeOnCancellation` does. */
    @Test
    fun cancellingAnUtteranceStopsTheVoice() = runTest {
        val synthesizer = MockSpeechSynthesizer().apply { speaksUntilStopped = true }

        val utterance = launch { synthesizer.speak("The weather in Vigo") }
        runCurrent()

        utterance.cancelAndJoin()

        assertEquals(
            "a cancelled utterance has to cut the voice, or the rider keeps hearing a page he left",
            1,
            synthesizer.stopCalls
        )
    }

    @Test
    fun aMockRecognizerCanReturnNullForNothingUnderstood() = runTest {
        val recognizer = MockSpeechRecognizer().apply { willHearNothing() }

        assertNull(
            "silence reaches the caller as null, never as an exception",
            recognizer.listen()
        )
    }

    /**
     * `assertThrows` cannot take a suspending lambda, and the mock never touches a scheduler, so
     * the one call that throws is bridged with `runBlocking` instead of `runTest`.
     */
    @Test
    fun aMockRecognizerCanThrowATypedFailure() {
        val recognizer = MockSpeechRecognizer().apply { willFail(RecognitionFailure.NO_PERMISSION) }

        val thrown = assertThrows(SpeechRecognitionException::class.java) {
            runBlocking { recognizer.listen() }
        }

        assertEquals(
            "the reason travels as a value, because the caller branches on it",
            RecognitionFailure.NO_PERMISSION,
            thrown.reason
        )
    }
}
