package com.opensab.app.source.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import androidx.core.content.ContextCompat
import com.opensab.domain.audio.AudioFocus
import com.opensab.domain.audio.AudioFocusKind
import com.opensab.domain.audio.AudioFocusLease
import com.opensab.domain.speech.BluetoothVoiceRouter
import com.opensab.domain.speech.RecognitionFailure
import com.opensab.domain.speech.RecognitionOutcome
import com.opensab.domain.speech.SpeechRecognitionException
import com.opensab.domain.speech.SpeechRecognizer
import com.opensab.domain.speech.recognitionOutcomeOf
import com.opensab.logging.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
import android.speech.SpeechRecognizer as PlatformSpeechRecognizer

/**
 * [SpeechRecognizer] over `android.speech.SpeechRecognizer`, imported under an alias because the
 * port has the same name.
 *
 * Sequences audio focus, Bluetooth voice routing (HFP/SCO) to the helmet intercom, the earcon, the
 * radio stabilization delay and the A2DP drain delay.
 *
 * The internal constructor hooks let the sequence be tested on the JVM.
 */
class AndroidSpeechRecognizer internal constructor(
    context: Context?,
    private val audioFocus: AudioFocus,
    private val voiceRouter: BluetoothVoiceRouter,
    private val recognizerSession: (suspend (Intent) -> String?)? = null,
    private val permissionChecker: (() -> Boolean)? = null,
    private val availabilityChecker: (() -> Boolean)? = null,
) : SpeechRecognizer {

    constructor(
        context: Context,
        audioFocus: AudioFocus,
        voiceRouter: BluetoothVoiceRouter,
    ) : this(
        context = context,
        audioFocus = audioFocus,
        voiceRouter = voiceRouter,
        recognizerSession = null,
        permissionChecker = null,
        availabilityChecker = null,
    )

    private val appContext: Context? = context?.applicationContext

    private companion object {
        private val EARCON_DELAY = 160.milliseconds
        private val RADIO_STABILIZATION = 150.milliseconds
        private val A2DP_DRAIN = 150.milliseconds
    }

    /**
     * `android.speech.SpeechRecognizer` is created, used and destroyed on the main thread or it
     * throws, so the whole body sits inside `Dispatchers.Main`, with a fresh instance per listen.
     *
     * Both refusals are thrown rather than returned as `null`: `null` is the rider having said
     * nothing, and a denied microphone must not look like a silent rider.
     *
     * The earcon gets 160 ms over A2DP before the link switches to SCO, and the radio 150 ms to
     * settle after it. Teardown is `NonCancellable` and waits 150 ms for A2DP to drain before the
     * focus lease is abandoned.
     */
    override suspend fun listen(locale: Locale?): String? = withContext(Dispatchers.Main) {
        if (!hasRecordAudioPermission()) {
            Log.w("No RECORD_AUDIO permission, so there is nothing to listen with")
            throw SpeechRecognitionException(RecognitionFailure.NO_PERMISSION)
        }
        if (!isRecognitionAvailable()) {
            Log.w("No speech recognizer is installed on this device")
            throw SpeechRecognitionException(RecognitionFailure.NO_RECOGNIZER)
        }

        delay(EARCON_DELAY)

        var lease: AudioFocusLease? = null
        var recognizer: PlatformSpeechRecognizer? = null
        try {
            lease = audioFocus.request(AudioFocusKind.TRANSIENT)
            voiceRouter.startVoiceRouting()
            delay(RADIO_STABILIZATION)

            if (recognizerSession != null) {
                recognizerSession.invoke(intentFor(locale))
            } else {
                val ctx = checkNotNull(appContext) { "appContext is required when running real recognizer" }
                val platformRecognizer = PlatformSpeechRecognizer.createSpeechRecognizer(ctx)
                recognizer = platformRecognizer
                suspendCancellableCoroutine { continuation ->
                    platformRecognizer.setRecognitionListener(recognitionListener(continuation))
                    continuation.invokeOnCancellation {
                        Log.d("The listen was cancelled; the recognizer goes with it")
                    }
                    platformRecognizer.startListening(intentFor(locale))
                }
            }
        } finally {
            withContext(NonCancellable) {
                recognizer?.destroy()
                voiceRouter.stopVoiceRouting()
                delay(A2DP_DRAIN)
                lease?.abandon()
            }
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        permissionChecker?.invoke() ?: (appContext != null &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED)

    private fun isRecognitionAvailable(): Boolean =
        availabilityChecker?.invoke() ?: (appContext != null &&
            PlatformSpeechRecognizer.isRecognitionAvailable(appContext))

    private fun intentFor(locale: Locale?): Intent =
        buildSpeechRecognizerIntent(appContext?.packageName ?: "com.opensab", locale)

    /**
     * Hands the error code and the result list to [recognitionOutcomeOf] untouched; the mapping
     * lives above the seam, where it has a test. Every other callback describes an utterance in
     * progress and is ignored.
     */
    private fun recognitionListener(
        continuation: CancellableContinuation<String?>
    ): RecognitionListener = object : RecognitionListener {

        override fun onResults(results: Bundle?) = continuation.deliver(
            recognitionOutcomeOf(
                results?.getStringArrayList(PlatformSpeechRecognizer.RESULTS_RECOGNITION)
            )
        )

        override fun onError(error: Int) = continuation.deliver(recognitionOutcomeOf(error))

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

/**
 * A transcript, a `null` for silence, or an exception for a failure the caller has to act on.
 *
 * `isActive` is checked because the recognizer can report an error after a result, or after the
 * rider has already left the page.
 */
private fun CancellableContinuation<String?>.deliver(outcome: RecognitionOutcome) {
    if (!isActive) return

    when (outcome) {
        is RecognitionOutcome.Understood -> resume(outcome.text)

        RecognitionOutcome.NothingUnderstood -> {
            Log.d("Nothing was understood, which is silence and not a failure")
            resume(null)
        }

        is RecognitionOutcome.Failed -> {
            Log.w("The recognizer failed: %s", outcome.reason)
            resumeWithException(SpeechRecognitionException(outcome.reason))
        }
    }
}

/**
 * `EXTRA_CALLING_PACKAGE` is required by some recognition engines. A null [locale] leaves
 * `EXTRA_LANGUAGE` out entirely, which is how the recognizer is told to use the system language;
 * an empty tag would not say the same thing.
 */
internal fun buildSpeechRecognizerIntent(
    callingPackage: String,
    locale: Locale?
): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
    )
    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)
    locale?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it.toLanguageTag()) }
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
}
