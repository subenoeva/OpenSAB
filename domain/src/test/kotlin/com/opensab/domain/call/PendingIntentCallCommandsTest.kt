package com.opensab.domain.call

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which handle each command routes to.
 *
 * Firing one is the single line the suite cannot reach, and that is exactly why the send is
 * injected: everything up to it is testable, and what is left is one call into the platform that
 * only a run on the motorcycle can close.
 *
 * The handles here are strings because `PendingIntent` is final with no public constructor and
 * there is no mocking library on the test classpath — which is why the class is generic over them.
 */
class PendingIntentCallCommandsTest {

    private val fired = mutableListOf<String>()

    private fun commands(
        answer: String? = ANSWER,
        decline: String? = DECLINE,
        hangUp: String? = HANG_UP,
        fallback: CallCommands? = null
    ) = PendingIntentCallCommands(
        answerIntent = answer,
        declineIntent = decline,
        hangUpIntent = hangUp,
        fallback = fallback,
        send = { fired += it }
    )

    @Test
    fun answerFiresTheAnswerIntent() {
        commands().answer()

        assertEquals(listOf(ANSWER), fired)
    }

    @Test
    fun rejectFiresTheDeclineIntent() {
        commands().reject()

        assertEquals(listOf(DECLINE), fired)
    }

    @Test
    fun disconnectFiresTheHangUpIntent() {
        commands().disconnect()

        assertEquals(listOf(HANG_UP), fired)
    }

    /**
     * A dialer that published `CallStyle` without its intents would leave the rider with a page and
     * no way to act on it. Nobody has been observed doing that, so the production wiring passes
     * `fallback = null` and this proves the door against a double instead.
     */
    @Test
    fun aMissingIntentFallsBackToTheStandby() {
        val fallback = CountingCallCommands()

        commands(answer = null, decline = null, hangUp = null, fallback = fallback).run {
            answer()
            reject()
            disconnect()
        }

        assertEquals(1, fallback.answers)
        assertEquals(1, fallback.rejects)
        assertEquals(1, fallback.disconnects)
        assertEquals(emptyList<String>(), fired)
    }

    /** With no fallback either, a missing intent is a no-op and never a crash. */
    @Test
    fun aMissingIntentWithNoFallbackDoesNothing() {
        commands(answer = null, fallback = null).answer()

        assertEquals(emptyList<String>(), fired)
    }

    private class CountingCallCommands : CallCommands {
        var answers = 0
        var rejects = 0
        var disconnects = 0

        override fun answer() { answers++ }
        override fun reject() { rejects++ }
        override fun disconnect() { disconnects++ }
    }

    private companion object {
        const val ANSWER = "android.answerIntent"
        const val DECLINE = "android.declineIntent"
        const val HANG_UP = "android.hangUpIntent"
    }
}
