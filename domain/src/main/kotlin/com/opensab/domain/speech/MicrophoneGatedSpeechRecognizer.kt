package com.opensab.domain.speech

import java.util.Locale

/**
 * Refuses a listen that Android would silence, instead of letting it run.
 *
 * A service started while the app was out of sight gets no microphone from the system: the
 * recognizer opens, audioserver feeds it zeros ("App op 27 missing, silencing record") and the
 * outcome is indistinguishable from a rider who said nothing. Asking first turns that silence into
 * [RecognitionFailure.MIC_NEEDS_APP], a failure the pages can word.
 *
 * [microphoneUsable] is asked on every listen and never cached: opening the app mid-session
 * promotes the service and the answer changes under a live page.
 */
class MicrophoneGatedSpeechRecognizer(
    private val delegate: SpeechRecognizer,
    private val microphoneUsable: () -> Boolean
) : SpeechRecognizer {

    override suspend fun listen(locale: Locale?): String? {
        if (!microphoneUsable()) throw SpeechRecognitionException(RecognitionFailure.MIC_NEEDS_APP)
        return delegate.listen(locale)
    }
}
