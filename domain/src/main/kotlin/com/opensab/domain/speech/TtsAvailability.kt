package com.opensab.domain.speech

/**
 * AOSP `android.speech.tts.TextToSpeech` status codes, copied by hand so [ttsAvailabilityOf] stays
 * testable on the JVM. Two domains, and `-1` lives in both: `ERROR` from `onInit` and
 * `LANG_MISSING_DATA` from `setLanguage`.
 */
internal object TtsStatusCodes {
    const val SUCCESS = 0
    const val ERROR = -1                    // init status
    const val LANG_MISSING_DATA = -1        // language status — same number, other domain
    const val LANG_NOT_SUPPORTED = -2
    const val LANG_AVAILABLE = 0
    const val LANG_COUNTRY_AVAILABLE = 1
    const val LANG_COUNTRY_VAR_AVAILABLE = 2
}

/**
 * Only [READY] has a voice. [NO_ENGINE] is a device with no text-to-speech engine at all,
 * [MISSING_DATA] an engine that knows the language but has not downloaded it, and [NOT_SUPPORTED]
 * an engine that does not know it. None of the three is an error the caller has to recover from:
 * they all mean the same thing to a page, which is that the frame is drawn and nothing is spoken.
 */
enum class TtsAvailability {
    READY, MISSING_DATA, NOT_SUPPORTED, NO_ENGINE;

    val canSpeak: Boolean get() = this == READY
}

/**
 * Takes both statuses and never one, because a lone `-1` cannot say which callback produced it.
 * The init status is read first and wins outright: a failed engine has no language to report, and
 * whatever `setLanguage` left in the second parameter says nothing about whether this phone can
 * talk.
 *
 * A language status that is none of the documented five is [TtsAvailability.NOT_SUPPORTED] rather
 * than assumed speakable: staying quiet on an unknown code costs a phrase, and guessing costs a
 * caller suspended on an engine that never speaks.
 *
 * With no engine, or with the language not downloaded, `speak()` returns straight away, logs, and
 * **does not throw**: the frame was the point and the speech the layer on top of it.
 */
fun ttsAvailabilityOf(initStatus: Int, languageStatus: Int): TtsAvailability {
    if (initStatus != TtsStatusCodes.SUCCESS) return TtsAvailability.NO_ENGINE

    return when (languageStatus) {
        TtsStatusCodes.LANG_AVAILABLE,
        TtsStatusCodes.LANG_COUNTRY_AVAILABLE,
        TtsStatusCodes.LANG_COUNTRY_VAR_AVAILABLE -> TtsAvailability.READY

        TtsStatusCodes.LANG_MISSING_DATA -> TtsAvailability.MISSING_DATA

        TtsStatusCodes.LANG_NOT_SUPPORTED -> TtsAvailability.NOT_SUPPORTED

        // Undocumented by AOSP, so it cannot be called speakable.
        else -> TtsAvailability.NOT_SUPPORTED
    }
}
