package com.opensab.domain.speech

import java.util.Locale

/**
 * One call, one utterance, and nothing survives between calls: the platform half creates and
 * destroys a recognizer per listen, because `android.speech.SpeechRecognizer` demands the main
 * thread and does not like being kept. So there is no state here for a caller to own, and no
 * `start`/`stop` pair to forget.
 */
interface SpeechRecognizer {

    /**
     * `null` means nothing was understood, and that is a normal outcome — the rider stayed silent.
     * A real failure — no permission, no recognizer on the
     * device, network, server — throws [SpeechRecognitionException] instead.
     *
     * The asymmetry with [SpeechSynthesizer.speak], which never throws, is deliberate. A recognizer
     * that fails changes the flow: there is nothing to send and the rider has to be told. An
     * utterance that fails changes nothing, because the frame is already on the cluster and the
     * voice was the layer on top of it.
     *
     * A null [locale] is the system language.
     */
    suspend fun listen(locale: Locale? = null): String?
}

/**
 * A listen that failed for a reason the caller has to act on, which is every reason except silence:
 * [RecognitionOutcome.NothingUnderstood] comes back as `null` and never as an exception.
 *
 * [reason] is a value and not a message because the caller branches on it, and a string would
 * force it to parse back what this layer already knew.
 */
class SpeechRecognitionException(val reason: RecognitionFailure) :
    Exception("Speech recognition failed: $reason")
