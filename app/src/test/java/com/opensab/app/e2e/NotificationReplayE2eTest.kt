package com.opensab.app.e2e

import com.opensab.domain.message.MessageNotification
import com.opensab.domain.message.MessageNotificationEvent
import com.opensab.sab.protocol.projection.ActiveIndicator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what each notification bus replays across a session start.
 *
 * `CallNotificationBus` keeps the call notifications that are live, and only those, so a call
 * already ringing when the rider connects still lights PHONE and mounts the call page — the source
 * only starts when the session does — while one that ended before he connected is not rebuilt.
 * `MessageNotificationBus` and `NavigationNotificationBus` replay nothing: a message is an event,
 * and reading it after the fact would tell the rider about something that already happened on the
 * phone before he ever connected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationReplayE2eTest {

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

    @Test
    fun `a call ringing before the session opens lights PHONE and takes the screen once connected`() = runTest {
        val harness = SessionHarness(backgroundScope)

        harness.callDriver.post(callerName = "Ana", callerNumber = "+34600000000")
        runCurrent()
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()

        assertTrue(
            "a call already ringing when the session opens has to be found by the collector that starts with it",
            ActiveIndicator.PHONE in harness.indicators.state.value
        )
        assertEquals(
            "and what the cluster was last told has to be what the publisher believes it left",
            harness.indicators.mask,
            harness.btu.indicatorMasks.last()
        )
        assertEquals(
            listOf("RootMenu", "CallPage"),
            harness.engine.stack.snapshot().map { it.name }
        )
    }

    @Test
    fun `a call that ended before the session opens neither lights PHONE nor takes the screen`() = runTest {
        val harness = SessionHarness(backgroundScope)

        harness.callDriver.post(callerName = "Ana", callType = 1)
        harness.callDriver.remove()
        harness.callDriver.post(callerName = "Ana", callType = 2)
        harness.callDriver.remove()
        runCurrent()
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()

        assertNull("a call that is over is not state", harness.calls.activeCall.value)
        assertFalse(
            "and no mask that ever reached the cluster carried PHONE",
            harness.btu.indicatorMasks.any { ActiveIndicator.PHONE.bit and it != 0 }
        )
        assertEquals("RootMenu", harness.engine.stack.peek()?.name)
    }

    @Test
    fun `a message posted before the session opens is not read once connected`() = runTest {
        val harness = SessionHarness(backgroundScope)

        harness.messageBus.post(whatsApp())
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()

        assertNull(
            "the message bus replays nothing: a message posted before anybody was collecting is gone",
            harness.message.currentMessage.value
        )
        assertFalse(
            "with no message read back, nothing lights MESSAGE",
            ActiveIndicator.MESSAGE in harness.indicators.state.value
        )
        assertEquals(
            "the carousel, not the message page, is what the rider finds",
            "RootMenu",
            harness.engine.stack.peek()?.name
        )
    }
}
