package com.opensab.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule this layer cannot afford to get wrong: not understanding is a normal outcome, and a
 * failure is not.
 *
 * Both halves are worth a test, but they are not worth the same. That silence stays silence is a
 * convenience; that a failure never turns into silence is the safety property, because silence is
 * what the caller treats as "the rider chose not to say anything" and walks on from. That is why a
 * code nobody has seen yet gets its own test.
 */
class RecognitionOutcomeTest {

    @Test
    fun noMatchIsNothingUnderstoodAndNotAFailure() {
        assertEquals(
            RecognitionOutcome.NothingUnderstood,
            recognitionOutcomeOf(RecognizerErrorCodes.ERROR_NO_MATCH)
        )
    }

    @Test
    fun speechTimeoutIsNothingUnderstoodAndNotAFailure() {
        assertEquals(
            RecognitionOutcome.NothingUnderstood,
            recognitionOutcomeOf(RecognizerErrorCodes.ERROR_SPEECH_TIMEOUT)
        )
    }

    /**
     * The recognizer is the one port that refuses out loud instead of returning nothing: a missing
     * `RECORD_AUDIO` is a wiring defect, and returning silence would hide it behind a rider who
     * looks like he never spoke.
     */
    @Test
    fun missingPermissionIsAFailure() {
        assertEquals(
            RecognitionOutcome.Failed(RecognitionFailure.NO_PERMISSION),
            recognitionOutcomeOf(RecognizerErrorCodes.ERROR_INSUFFICIENT_PERMISSIONS)
        )
    }

    @Test
    fun networkErrorsAreFailures() {
        val codes = listOf(
            RecognizerErrorCodes.ERROR_NETWORK,
            RecognizerErrorCodes.ERROR_NETWORK_TIMEOUT
        )

        for (code in codes) {
            assertEquals(
                "error code $code",
                RecognitionOutcome.Failed(RecognitionFailure.NETWORK),
                recognitionOutcomeOf(code)
            )
        }
    }

    @Test
    fun serverErrorsAreFailures() {
        val codes = listOf(
            RecognizerErrorCodes.ERROR_SERVER,
            RecognizerErrorCodes.ERROR_SERVER_DISCONNECTED
        )

        for (code in codes) {
            assertEquals(
                "error code $code",
                RecognitionOutcome.Failed(RecognitionFailure.SERVER),
                recognitionOutcomeOf(code)
            )
        }
    }

    @Test
    fun languageErrorsAreFailures() {
        val codes = listOf(
            RecognizerErrorCodes.ERROR_LANGUAGE_NOT_SUPPORTED,
            RecognizerErrorCodes.ERROR_LANGUAGE_UNAVAILABLE
        )

        for (code in codes) {
            assertEquals(
                "error code $code",
                RecognitionOutcome.Failed(RecognitionFailure.LANGUAGE),
                recognitionOutcomeOf(code)
            )
        }
    }

    /**
     * The half that matters. Android adds error codes; this table is a hand copy and will lag
     * behind at some point. When it does, the unknown code has to surface as a failure — a new
     * code that becomes silence is a message the rider believes he sent.
     */
    @Test
    fun anUnknownCodeIsAFailureAndNotSilence() {
        val futureCodes = listOf(15, 99, Int.MAX_VALUE)

        for (code in futureCodes) {
            assertEquals(
                "error code $code",
                RecognitionOutcome.Failed(RecognitionFailure.UNKNOWN),
                recognitionOutcomeOf(code)
            )
        }
    }

    @Test
    fun emptyResultsAreNothingUnderstood() {
        assertEquals(RecognitionOutcome.NothingUnderstood, recognitionOutcomeOf(emptyList()))
        assertEquals(RecognitionOutcome.NothingUnderstood, recognitionOutcomeOf(results = null))
    }

    /**
     * A recognizer that hears breath or wind answers with a result that is technically there and
     * says nothing. It counts as silence, not as a transcript of one space.
     */
    @Test
    fun blankResultsAreNothingUnderstood() {
        assertEquals(RecognitionOutcome.NothingUnderstood, recognitionOutcomeOf(listOf(" ")))
        assertEquals(RecognitionOutcome.NothingUnderstood, recognitionOutcomeOf(listOf("", "   ")))
    }

    @Test
    fun theFirstNonBlankResultIsTheTranscript() {
        assertEquals(
            RecognitionOutcome.Understood("turn left at the roundabout"),
            recognitionOutcomeOf(listOf("  ", "  turn left at the roundabout  ", "turn right"))
        )
    }
}
