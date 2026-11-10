package com.opensab.app.source.speech

import android.content.Intent
import android.speech.RecognizerIntent
import com.opensab.domain.audio.AudioFocusKind
import com.opensab.domain.audio.RecordingAudioFocus
import com.opensab.domain.speech.MockBluetoothVoiceRouter
import com.opensab.domain.speech.RecognitionFailure
import com.opensab.domain.speech.SpeechRecognitionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSpeechRecognizerLifecycleTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun delaySequencingAndOrderingOnSuccessfulListen() = runTest(testDispatcher) {
        val audioFocus = RecordingAudioFocus()
        val voiceRouter = MockBluetoothVoiceRouter().apply { isHeadsetConnected = true }
        var sessionStarted = false
        var sessionCompleted = false

        val recognizer = AndroidSpeechRecognizer(
            context = null,
            audioFocus = audioFocus,
            voiceRouter = voiceRouter,
            permissionChecker = { true },
            availabilityChecker = { true },
            recognizerSession = {
                sessionStarted = true
                delay(500.milliseconds)
                sessionCompleted = true
                "voice result"
            }
        )

        var result: String? = null
        val job = launch {
            result = recognizer.listen()
        }

        // at t=0 ms: audio focus not requested yet
        runCurrent()
        assertEquals(0, audioFocus.requests.size)
        assertEquals(0, voiceRouter.startCalls)
        assertFalse(sessionStarted)

        // at t=160 ms: AudioFocusKind.TRANSIENT requested and voiceRouter.startVoiceRouting() called
        advanceTimeBy(160.milliseconds)
        runCurrent()
        assertEquals(listOf(AudioFocusKind.TRANSIENT), audioFocus.requests)
        assertEquals(1, voiceRouter.startCalls)
        assertTrue(voiceRouter.isRoutingActive)
        assertFalse(sessionStarted)

        // at t=310 ms (160 + 150): recognition starts
        advanceTimeBy(150.milliseconds)
        runCurrent()
        assertTrue(sessionStarted)
        assertFalse(sessionCompleted)
        assertEquals(0, voiceRouter.stopCalls)
        assertEquals(0, audioFocus.abandoned.size)

        // recognition runs for 500 ms (until t=810 ms)
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertTrue(sessionCompleted)
        // on completion: voiceRouter.stopVoiceRouting() called
        assertEquals(1, voiceRouter.stopCalls)
        assertFalse(voiceRouter.isRoutingActive)
        // at t=+0 ms after completion (drain in progress): audioFocus.abandon() not called yet
        assertEquals(0, audioFocus.abandoned.size)
        assertEquals(listOf(AudioFocusKind.TRANSIENT), audioFocus.held)

        // at t=+150 ms (A2DP drain): audioFocus.abandon() called
        advanceTimeBy(150.milliseconds)
        runCurrent()
        assertEquals(listOf(AudioFocusKind.TRANSIENT), audioFocus.abandoned)
        assertTrue(audioFocus.held.isEmpty())
        assertTrue(job.isCompleted)
        assertEquals("voice result", result)
    }

    @Test
    fun permissionDeniedThrowsNoPermissionWithoutRequestingFocusOrRouting() = runTest(testDispatcher) {
        val audioFocus = RecordingAudioFocus()
        val voiceRouter = MockBluetoothVoiceRouter()

        val recognizer = AndroidSpeechRecognizer(
            context = null,
            audioFocus = audioFocus,
            voiceRouter = voiceRouter,
            permissionChecker = { false },
            availabilityChecker = { true }
        )

        try {
            recognizer.listen()
            fail("Expected SpeechRecognitionException(NO_PERMISSION)")
        } catch (e: SpeechRecognitionException) {
            assertEquals(RecognitionFailure.NO_PERMISSION, e.reason)
        }

        assertEquals(0, audioFocus.requests.size)
        assertEquals(0, voiceRouter.startCalls)
    }

    @Test
    fun recognizerUnavailableThrowsNoRecognizerWithoutRequestingFocusOrRouting() = runTest(testDispatcher) {
        val audioFocus = RecordingAudioFocus()
        val voiceRouter = MockBluetoothVoiceRouter()

        val recognizer = AndroidSpeechRecognizer(
            context = null,
            audioFocus = audioFocus,
            voiceRouter = voiceRouter,
            permissionChecker = { true },
            availabilityChecker = { false }
        )

        try {
            recognizer.listen()
            fail("Expected SpeechRecognitionException(NO_RECOGNIZER)")
        } catch (e: SpeechRecognitionException) {
            assertEquals(RecognitionFailure.NO_RECOGNIZER, e.reason)
        }

        assertEquals(0, audioFocus.requests.size)
        assertEquals(0, voiceRouter.startCalls)
    }

    @Test
    fun cancellationDuringRecognitionUnrollsCleanlyWithDrainDelay() = runTest(testDispatcher) {
        val audioFocus = RecordingAudioFocus()
        val voiceRouter = MockBluetoothVoiceRouter().apply { isHeadsetConnected = true }

        val recognizer = AndroidSpeechRecognizer(
            context = null,
            audioFocus = audioFocus,
            voiceRouter = voiceRouter,
            permissionChecker = { true },
            availabilityChecker = { true },
            recognizerSession = {
                awaitCancellation()
            }
        )

        val job = launch {
            recognizer.listen()
        }

        advanceTimeBy(310.milliseconds)
        runCurrent()
        assertTrue(voiceRouter.isRoutingActive)
        assertEquals(listOf(AudioFocusKind.TRANSIENT), audioFocus.held)

        // Cancel job during active listening
        job.cancel()
        runCurrent()

        // Stop routing called immediately
        assertEquals(1, voiceRouter.stopCalls)
        assertFalse(voiceRouter.isRoutingActive)
        // Focus still held during 150 ms drain delay
        assertEquals(0, audioFocus.abandoned.size)
        assertEquals(listOf(AudioFocusKind.TRANSIENT), audioFocus.held)

        // Advance through drain delay
        advanceTimeBy(150.milliseconds)
        runCurrent()

        // Focus is abandoned and job cancellation completes
        assertEquals(listOf(AudioFocusKind.TRANSIENT), audioFocus.abandoned)
        assertTrue(audioFocus.held.isEmpty())
        assertTrue(job.isCancelled)
    }

    @Test
    fun exceptionDuringRecognitionUnrollsCleanlyWithDrainDelay() = runTest(testDispatcher) {
        val audioFocus = RecordingAudioFocus()
        val voiceRouter = MockBluetoothVoiceRouter().apply { isHeadsetConnected = true }

        val recognizer = AndroidSpeechRecognizer(
            context = null,
            audioFocus = audioFocus,
            voiceRouter = voiceRouter,
            permissionChecker = { true },
            availabilityChecker = { true },
            recognizerSession = {
                throw SpeechRecognitionException(RecognitionFailure.NETWORK)
            }
        )

        var thrownException: Throwable? = null
        launch {
            try {
                recognizer.listen()
            } catch (t: Throwable) {
                thrownException = t
            }
        }

        advanceTimeBy(310.milliseconds)
        runCurrent()

        // Exception thrown inside session: finally runs, stops voice routing
        assertEquals(1, voiceRouter.stopCalls)
        assertFalse(voiceRouter.isRoutingActive)
        // Drain delay is active: focus still held
        assertEquals(0, audioFocus.abandoned.size)
        assertEquals(listOf(AudioFocusKind.TRANSIENT), audioFocus.held)

        // Advance drain delay
        advanceTimeBy(150.milliseconds)
        runCurrent()

        // Focus is now abandoned and exception propagated
        assertEquals(listOf(AudioFocusKind.TRANSIENT), audioFocus.abandoned)
        assertTrue(audioFocus.held.isEmpty())
        assertTrue(thrownException is SpeechRecognitionException)
        assertEquals(RecognitionFailure.NETWORK, (thrownException as SpeechRecognitionException).reason)
    }

    @Test
    fun listenProceedsEvenWhenAudioFocusIsDenied() = runTest(testDispatcher) {
        val audioFocus = RecordingAudioFocus(granted = false)
        val voiceRouter = MockBluetoothVoiceRouter().apply { isHeadsetConnected = true }

        val recognizer = AndroidSpeechRecognizer(
            context = null,
            audioFocus = audioFocus,
            voiceRouter = voiceRouter,
            permissionChecker = { true },
            availabilityChecker = { true },
            recognizerSession = {
                "audible despite denied focus"
            }
        )

        var result: String? = null
        val job = launch {
            result = recognizer.listen()
        }

        advanceTimeBy((160 + 150).milliseconds)
        runCurrent()
        assertEquals(listOf(AudioFocusKind.TRANSIENT), audioFocus.requests)
        assertEquals(0, audioFocus.abandoned.size)
        assertEquals(1, voiceRouter.startCalls)

        advanceTimeBy(150.milliseconds)
        runCurrent()
        assertEquals(1, voiceRouter.stopCalls)
        assertTrue(job.isCompleted)
        assertEquals("audible despite denied focus", result)
    }

    @Test
    fun sessionReceivesBuiltIntentWithLocale() = runTest(testDispatcher) {
        val audioFocus = RecordingAudioFocus()
        val voiceRouter = MockBluetoothVoiceRouter()
        var capturedIntent: Intent? = null

        val recognizer = AndroidSpeechRecognizer(
            context = null,
            audioFocus = audioFocus,
            voiceRouter = voiceRouter,
            permissionChecker = { true },
            availabilityChecker = { true },
            recognizerSession = { intent: Intent ->
                capturedIntent = intent
                "matched"
            }
        )

        launch {
            recognizer.listen(Locale.GERMANY)
        }

        advanceTimeBy((160 + 150 + 150).milliseconds)
        runCurrent()

        assertEquals("de-DE", capturedIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
    }
}
