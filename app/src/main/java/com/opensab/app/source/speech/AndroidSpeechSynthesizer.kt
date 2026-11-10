package com.opensab.app.source.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.opensab.domain.speech.SpeechSynthesizer
import com.opensab.domain.speech.ttsAvailabilityOf
import com.opensab.logging.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * [SpeechSynthesizer] over `android.speech.tts.TextToSpeech`.
 *
 * The engine is created on the first [speak] and never released: re-initializing costs seconds and
 * the rider is already moving. Nothing is bound until somebody speaks.
 */
class AndroidSpeechSynthesizer(context: Context) : SpeechSynthesizer {

    private val appContext = context.applicationContext

    private val creation = Mutex()

    @Volatile
    private var bound: BoundEngine? = null

    private val utterances = AtomicLong()

    /**
     * Both statuses go to [ttsAvailabilityOf] raw, because the same `-1` means "no engine" from
     * `onInit` and "language not downloaded" from `setLanguage`. `setLanguage` is safe on an engine
     * that never started: AOSP answers `LANG_NOT_SUPPORTED`.
     *
     * With no voice this returns silently and without throwing: the frame is already on the cluster
     * and the speech is only the layer on top of it.
     */
    override suspend fun speak(text: String, locale: Locale?) {
        val engine = engine()
        val language = locale ?: Locale.getDefault()

        val initStatus = engine.initStatus.await()
        val languageStatus = if (engine.currentLanguage == language) {
            TextToSpeech.LANG_AVAILABLE
        } else {
            val status = engine.tts.setLanguage(language)
            if (status >= TextToSpeech.LANG_AVAILABLE) {
                engine.currentLanguage = language
            }
            status
        }

        val availability = ttsAvailabilityOf(initStatus, languageStatus)
        if (!availability.canSpeak) {
            Log.w("Nothing spoken: text-to-speech is %s for %s", availability, language)
            return
        }

        utter(engine.tts, text)
    }

    /** Cuts the voice and leaves the engine bound. */
    override fun stop() {
        bound?.tts?.stop()
    }

    /**
     * The lock covers the creation and not the wait: `onInit` can take a second or two, and holding
     * it across that would queue every later utterance behind the first one.
     */
    private suspend fun engine(): BoundEngine = creation.withLock {
        bound ?: create().also { bound = it }
    }

    private fun create(): BoundEngine {
        val initStatus = CompletableDeferred<Int>()
        // `onInit` lands on whichever thread the engine connected on; completing is safe from all.
        val tts = TextToSpeech(appContext) { status -> initStatus.complete(status) }
        // Same usage as the TRANSIENT_MAY_DUCK focus PageVoice holds while it speaks: guidance, which
        // media ducks for. The attributes are stored by the engine and apply once it binds.
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        return BoundEngine(tts, initStatus)
    }

    /**
     * Suspends until the engine says the utterance is over, however it ended.
     *
     * `QUEUE_FLUSH` because the newest thing the page has to say replaces whatever was still being
     * said. Cancelling is a normal end, not a failure: it cuts the voice, so a rider who presses
     * BACK mid-sentence does not hear out a page he has already left.
     */
    private suspend fun utter(tts: TextToSpeech, text: String) {
        val utteranceId = "opensab-" + utterances.incrementAndGet()

        suspendCancellableCoroutine { continuation ->
            tts.setOnUtteranceProgressListener(progressListener(utteranceId, continuation))
            continuation.invokeOnCancellation {
                Log.d("Utterance %s was cancelled, cutting the voice", utteranceId)
                tts.stop()
            }

            val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (queued != TextToSpeech.SUCCESS && continuation.isActive) {
                // Nothing queued means no callback: resume, or the caller waits forever.
                Log.w("The engine refused utterance %s", utteranceId)
                continuation.resume(Unit)
            }
        }
    }

    private fun progressListener(
        utteranceId: String,
        continuation: CancellableContinuation<Unit>
    ): UtteranceProgressListener = object : UtteranceProgressListener() {

        override fun onStart(id: String?) = Unit

        override fun onDone(id: String?) = finish(id)

        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) {
            Log.w("The engine failed while saying utterance %s", id)
            finish(id)
        }

        override fun onError(id: String?, errorCode: Int) {
            Log.w("The engine failed while saying utterance %s, code %d", id, errorCode)
            finish(id)
        }

        override fun onStop(id: String?, interrupted: Boolean) = finish(id)

        /**
         * The listener belongs to the engine and not to the utterance, so a callback about the one
         * `QUEUE_FLUSH` just cut can still land here; only our id ends this suspension.
         */
        private fun finish(id: String?) {
            if (id == utteranceId && continuation.isActive) continuation.resume(Unit)
        }
    }

    /**
     * A [CompletableDeferred] rather than a field the callback writes, because the wait is
     * cancellable and shared: a rider who leaves the page while the engine is still starting
     * cancels his own wait and not the engine the next page is about to use.
     */
    private class BoundEngine(
        val tts: TextToSpeech,
        val initStatus: CompletableDeferred<Int>,
        @Volatile var currentLanguage: Locale? = null
    )
}
