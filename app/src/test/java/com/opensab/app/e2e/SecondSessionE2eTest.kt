package com.opensab.app.e2e

import com.opensab.domain.message.MessageNotification
import com.opensab.domain.message.MessageNotificationEvent
import com.opensab.sab.protocol.projection.ActiveIndicator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Two sessions in the same `@Singleton` process, back to back — the shape a rider actually lives:
 * contact off, contact on again a minute later, same phone process the whole time. Both tests are
 * pins, so a regression here — a `SessionParticipant` that forgets to release, or a page factory
 * that stops being memoized — shows up as a session-owner failure, not a message or a call one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecondSessionE2eTest {

    private fun whatsApp() = MessageNotificationEvent.Posted(
        MessageNotification(
            packageName = "com.whatsapp",
            key = "0|com.whatsapp|1|null|10123",
            title = "Elena",
            text = "Are you far",
            conversationTitle = null,
            isGroupConversation = false,
            isGroupSummary = false,
            isOngoing = false,
            postTimeMillis = 1_700_000_000_000L
        )
    )

    /**
     * `ActiveIndicatorsPublisher.forget()` runs as the first close action of every session, so a
     * message posted in the second session must still light MESSAGE.
     */
    @Test
    fun `a second session in the same process lights MESSAGE again`() = runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()
        harness.messageBus.post(whatsApp())
        runCurrent()
        assertEquals(
            ActiveIndicator.maskOf(setOf(ActiveIndicator.MESSAGE)),
            harness.indicators.mask
        )

        harness.disconnect()
        runCurrent()
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()
        harness.messageBus.post(whatsApp())
        runCurrent()

        assertEquals(
            "the second session's mask must start empty and light up the same way the first did",
            ActiveIndicator.maskOf(setOf(ActiveIndicator.MESSAGE)),
            harness.indicators.mask
        )
    }

    /**
     * `BaseStateManager` — and the `CallPageProvider` it builds once — is `@Singleton`, so the same
     * instance answers a ringing call across two sessions.
     */
    @Test
    fun `the call page is the same instance across two sessions`() = runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.callDriver.post(callerName = "Ana", callerNumber = "+34600000000")
        runCurrent()
        val firstCallPage = harness.engine.stack.peek()
        harness.callDriver.remove()
        runCurrent()

        harness.disconnect()
        runCurrent()
        harness.connect().getOrThrow()
        harness.callDriver.post(callerName = "Ana", callerNumber = "+34600000000")
        runCurrent()
        val secondCallPage = harness.engine.stack.peek()
        harness.callDriver.remove()
        runCurrent()

        assertSame(
            "the call page is built once for the @Singleton engine: unchanged across sessions",
            firstCallPage,
            secondCallPage
        )
    }
}
