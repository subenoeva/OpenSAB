package com.opensab.domain.speech

import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/** **Owner: the test that constructs it.** */
class MockBluetoothVoiceRouter : BluetoothVoiceRouter {
    override var isHeadsetConnected: Boolean = false
    override var isRoutingActive: Boolean = false
        private set

    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set

    override suspend fun startVoiceRouting(): Boolean {
        startCalls++
        if (isHeadsetConnected) {
            isRoutingActive = true
            return true
        }
        return false
    }

    override suspend fun stopVoiceRouting() {
        stopCalls++
        isRoutingActive = false
    }

    fun reset() {
        isHeadsetConnected = false
        isRoutingActive = false
        startCalls = 0
        stopCalls = 0
    }
}

/** **Owner: the test that constructs it.** */
class MockSpeechRecognizer : SpeechRecognizer {

    /** How many times someone listened. */
    var listenCalls = 0
        private set

    /** The locale of the last listen; null is the system language. */
    var lastLocale: Locale? = null
        private set

    private var outcome: RecognitionOutcome = RecognitionOutcome.NothingUnderstood

    /**
     * When true, [listen] suspends until [release] instead of answering at once: it is the long
     * listen that has to still be open when the rider leaves the page. The default is the short
     * one, so a test that does not care about the microphone being open never has to close it.
     *
     * The counterpart of [MockSpeechSynthesizer.speaksUntilStopped], for the sharper half of the
     * same rule: an utterance nobody cut merely finishes, while a listen nobody cut keeps the
     * microphone open.
     */
    var listensUntilReleased = false

    private var pending: CancellableContinuation<String?>? = null

    /** Ends a suspended listen with [text], or with silence when it is null. */
    fun release(text: String? = null) {
        val waiting = pending
        pending = null
        waiting?.resume(text)
    }

    /** The next listen understands [text]. */
    fun willHear(text: String) {
        outcome = RecognitionOutcome.Understood(text)
    }

    /** The next listen hears silence, which is an outcome and not a failure. */
    fun willHearNothing() {
        outcome = RecognitionOutcome.NothingUnderstood
    }

    /** The next listen fails with [reason], the way a denied microphone or a dead network does. */
    fun willFail(reason: RecognitionFailure) {
        outcome = RecognitionOutcome.Failed(reason)
    }

    /**
     * Where the asymmetry of [SpeechRecognizer.listen] becomes checkable without a microphone:
     * silence leaves as `null`, and only a real failure throws.
     *
     * A mock that answered with a transcript whatever it was configured with would let the
     * consumers above be written against a recognizer that never stays quiet and never fails —
     * which are precisely the two branches a rider on a bike will meet first. The `when` is
     * exhaustive over [RecognitionOutcome] and has no `else`, so a fourth outcome would not
     * compile until someone decides which side of the asymmetry it falls on.
     */
    override suspend fun listen(locale: Locale?): String? {
        listenCalls++
        lastLocale = locale
        if (listensUntilReleased) {
            // Nothing is intercepted here either: cancelling a listen is how leaving the page
            // closes the microphone, and the `CancellationException` has to reach the job.
            return suspendCancellableCoroutine { continuation -> pending = continuation }
        }
        return when (val configured = outcome) {
            is RecognitionOutcome.Understood -> configured.text
            RecognitionOutcome.NothingUnderstood -> null
            is RecognitionOutcome.Failed -> throw SpeechRecognitionException(configured.reason)
        }
    }
}

/**
 * What it makes checkable is the sentence this layer exists for — "the page said this, and then it
 * painted that" — with no phone, no engine and no downloaded language in the way.
 *
 * **Owner: the test that constructs it.**
 */
class MockSpeechSynthesizer : SpeechSynthesizer {

    /** One utterance as it was asked for. A null [locale] is the system language. */
    data class Utterance(val text: String, val locale: Locale?)

    /** Everything spoken, in order. */
    val utterances = mutableListOf<Utterance>()

    /** How many times the voice was cut, by [stop] or by cancelling the utterance. */
    var stopCalls = 0
        private set

    /**
     * When true, [speak] suspends until the voice is cut instead of returning at once: it is the
     * long utterance that has to still be running when the rider presses BACK. The default is the
     * short one, so a page test never has to finish an utterance it did not care about.
     */
    var speaksUntilStopped = false

    private var pending: CancellableContinuation<Unit>? = null

    /**
     * Suspends the way the platform half does, and cuts the voice from the same place it does:
     * `invokeOnCancellation { tts.stop() }`. Cancelling is the rider
     * pressing BACK mid-sentence, and it has to reach the engine, or he keeps hearing a page he
     * has already left.
     *
     * Nothing here intercepts a thing, and that half is load-bearing. The `CancellationException`
     * reaches the caller untouched, because cancelling an utterance is a normal outcome and not a
     * failure; what breaks that rule is one stray handler around this suspension. Resuming a
     * continuation that is already cancelled is ignored by design, so [stop] can stay the single
     * way the voice is cut on both paths.
     */
    override suspend fun speak(text: String, locale: Locale?) {
        utterances += Utterance(text, locale)
        if (!speaksUntilStopped) return

        suspendCancellableCoroutine { continuation ->
            pending = continuation
            continuation.invokeOnCancellation { stop() }
        }
    }

    override fun stop() {
        stopCalls++
        val cut = pending
        pending = null
        cut?.resume(Unit)
    }
}
