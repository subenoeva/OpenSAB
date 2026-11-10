package com.opensab.domain.speech

/**
 * The error codes `android.speech.SpeechRecognizer` hands to `onError`, copied by hand so that the
 * decision they feed — silence or failure — stays above the seam and testable on the JVM.
 *
 * Values follow AOSP `frameworks/base/core/java/android/speech/SpeechRecognizer.java`. They are
 * public API and cannot drift; what can happen is a new one appearing, and that case is the point
 * of [recognitionOutcomeOf].
 */
internal object RecognizerErrorCodes {
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5

    /** The rider opened his mouth and nothing came out. Not a failure: see below. */
    const val ERROR_SPEECH_TIMEOUT = 6

    /** Sound arrived and meant nothing. Not a failure either. */
    const val ERROR_NO_MATCH = 7

    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    @Suppress("unused") // AOSP code 10, kept so the table has no holes.
    const val ERROR_TOO_MANY_REQUESTS = 10
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13
    @Suppress("unused") // AOSP code 14, kept so the table has no holes.
    const val ERROR_CANNOT_CHECK_SUPPORT = 14
}

/**
 * Every value here changes the caller's flow: there is nothing to send, and the rider has to be
 * told. That is the whole difference from [RecognitionOutcome.NothingUnderstood], which changes
 * nothing and is a normal end of the interaction.
 *
 * [NO_RECOGNIZER] is the one value no error code produces, which is why [recognitionOutcomeOf] has
 * no branch for it: `AndroidSpeechRecognizer` (`:app`) raises it when `isRecognitionAvailable` answers false,
 * before anything is created and before there is a listener to report a code.
 */
enum class RecognitionFailure {
    NO_PERMISSION,
    NO_RECOGNIZER,
    NETWORK,
    AUDIO,
    SERVER,
    CLIENT,
    BUSY,
    LANGUAGE,
    UNKNOWN,

    /**
     * Raised by [MicrophoneGatedSpeechRecognizer] before any recognizer exists: Android would record
     * zeros, because the session's foreground service holds no microphone type. No error code
     * produces it, for the same reason as [NO_RECOGNIZER].
     */
    MIC_NEEDS_APP
}

/**
 * How one listen ended, before the port turns it into a `String?` or an exception.
 *
 * The three cases exist as a type instead of as a nullable string plus a comment because the middle
 * one is the one that gets lost: without a name of its own, "nothing understood" ends up handled as
 * an error by whoever reads the code next.
 */
sealed interface RecognitionOutcome {

    /** Something was said and understood. [text] is already trimmed. */
    data class Understood(val text: String) : RecognitionOutcome

    /** Not an error: the rider said nothing, or nothing intelligible. */
    data object NothingUnderstood : RecognitionOutcome

    /** A real failure, the kind the caller has to react to. */
    data class Failed(val reason: RecognitionFailure) : RecognitionOutcome
}

/**
 * [RecognizerErrorCodes.ERROR_NO_MATCH] and [RecognizerErrorCodes.ERROR_SPEECH_TIMEOUT] are
 * [RecognitionOutcome.NothingUnderstood] and not a failure: the rider stayed silent.
 *
 * Every other code is a [RecognitionOutcome.Failed], and that deliberately includes codes this
 * table has never seen. The asymmetry is the point: silence is an outcome the caller acts on, so a
 * code added by a future Android release must not quietly become silence. An unknown failure that
 * is reported is a bug someone can find; an unknown failure dressed as silence is a rider whose
 * message was never sent and who was never told.
 *
 * So the `else` branch is load-bearing rather than a formality the compiler asks for on an `Int`:
 * it is where every code AOSP has not invented yet lands, and it lands on
 * [RecognitionFailure.UNKNOWN]. Adding a code to [RecognizerErrorCodes] without a branch here
 * degrades it to `UNKNOWN`; it never turns it into silence.
 */
fun recognitionOutcomeOf(errorCode: Int): RecognitionOutcome = when (errorCode) {
    RecognizerErrorCodes.ERROR_NO_MATCH,
    RecognizerErrorCodes.ERROR_SPEECH_TIMEOUT -> RecognitionOutcome.NothingUnderstood

    RecognizerErrorCodes.ERROR_INSUFFICIENT_PERMISSIONS ->
        RecognitionOutcome.Failed(RecognitionFailure.NO_PERMISSION)

    RecognizerErrorCodes.ERROR_NETWORK,
    RecognizerErrorCodes.ERROR_NETWORK_TIMEOUT ->
        RecognitionOutcome.Failed(RecognitionFailure.NETWORK)

    RecognizerErrorCodes.ERROR_SERVER,
    RecognizerErrorCodes.ERROR_SERVER_DISCONNECTED ->
        RecognitionOutcome.Failed(RecognitionFailure.SERVER)

    RecognizerErrorCodes.ERROR_LANGUAGE_NOT_SUPPORTED,
    RecognizerErrorCodes.ERROR_LANGUAGE_UNAVAILABLE ->
        RecognitionOutcome.Failed(RecognitionFailure.LANGUAGE)

    RecognizerErrorCodes.ERROR_AUDIO -> RecognitionOutcome.Failed(RecognitionFailure.AUDIO)
    RecognizerErrorCodes.ERROR_CLIENT -> RecognitionOutcome.Failed(RecognitionFailure.CLIENT)
    RecognizerErrorCodes.ERROR_RECOGNIZER_BUSY -> RecognitionOutcome.Failed(RecognitionFailure.BUSY)

    else -> RecognitionOutcome.Failed(RecognitionFailure.UNKNOWN)
}

/**
 * Nothing here can fail: a null, empty or all-blank list is the rider having said nothing, which is
 * the same [RecognitionOutcome.NothingUnderstood] as a no-match code. The transcript is the first
 * entry that carries something — the listener asks for `EXTRA_MAX_RESULTS = 1`, so in practice
 * there is one — and it arrives trimmed, because what surrounds it is the recognizer's padding and
 * never part of what was said.
 */
fun recognitionOutcomeOf(results: List<String>?): RecognitionOutcome =
    when (val spoken = results?.firstOrNull { it.isNotBlank() }) {
        null -> RecognitionOutcome.NothingUnderstood
        else -> RecognitionOutcome.Understood(spoken.trim())
    }
