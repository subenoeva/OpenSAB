package com.opensab.app.e2e

import com.opensab.domain.message.MessageNotification
import com.opensab.domain.message.MessageNotificationEvent
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ActiveIndicator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A message from the phone to the bytes on the cable, over the graph Hilt builds and with the
 * presses entering through the wire.
 *
 * `BACK` gives the screen back and the MESSAGE bit **stays lit**, because `@Back` is a `YIELD`
 * with `PageArgs(ActiveIndicators.Type.MESSAGE, true)` and not a discard. And the body never lands
 * on the cluster unannounced: an arriving message mounts the prompt, which says who wrote and on
 * which app, and the reading is one `SELECT` away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageE2eTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun whatsApp(
        key: String = "0|com.whatsapp|1|null|10123",
        title: String = "Elena",
        text: String = "Are you far"
    ) = MessageNotificationEvent.Posted(
        MessageNotification(
            packageName = "com.whatsapp",
            key = key,
            title = title,
            text = text,
            conversationTitle = null,
            isGroupConversation = false,
            isGroupSummary = false,
            isOngoing = false,
            postTimeMillis = 1_700_000_000_000L
        )
    )

    @Test
    fun `an arriving message paints the sender and the app it came from`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()

        harness.messageBus.post(whatsApp())
        runCurrent()

        val painted = harness.btu.baseFrames().last().payload
        assertTrue("the sender has to reach the cluster", painted.containsAscii("Elena"))
        assertTrue("and the app it came from", painted.containsAscii("WhatsApp"))
        assertFalse(
            "the body is not read to the rider until he says so",
            painted.containsAscii("Are you far")
        )
        assertEquals(
            "MESSAGE has to be lit while it is unread",
            ActiveIndicator.maskOf(setOf(ActiveIndicator.MESSAGE)),
            harness.indicators.mask
        )
    }

    /**
     * Over the wire and out loud: a message arrives, the cluster asks, the rider says yes, and the
     * body is spoken — once, and in the voice of the alphabet it arrived in.
     */
    @Test
    fun `play reads the body out loud`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()
        harness.messageBus.post(whatsApp())
        runCurrent()
        val askedBeforePlay = harness.spoken.utterances.size

        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        val read = harness.spoken.utterances.drop(askedBeforePlay)
        assertEquals("the body is read once and only once", 1, read.size)
        assertTrue("and it is the body", read.single().text.contains("Are you far"))
        assertNull("a latin body keeps the rider's voice", read.single().locale)
    }

    /** `@Select Play`: the answer to the question is the reading, on the same claim. */
    @Test
    fun `play paints the body on the cluster`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()
        harness.messageBus.post(whatsApp())
        runCurrent()

        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        val painted = harness.btu.baseFrames().last().payload
        assertEquals("MessagePage", harness.engine.stack.peek()?.name)
        assertTrue("the body has to reach the cluster now", painted.containsAscii("Are you far"))
    }

    @Test
    fun `back gives the screen back and leaves the bit lit`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()
        harness.messageBus.post(whatsApp())
        runCurrent()

        harness.btu.press(FunctionId.BACK)
        runCurrent()

        assertEquals(
            "Ignore must leave the rider on the carousel",
            "RootMenu",
            harness.engine.stack.peek()?.name
        )
        assertEquals(
            "yielding the screen does not throw the message away",
            ActiveIndicator.maskOf(setOf(ActiveIndicator.MESSAGE)),
            harness.indicators.mask
        )
        assertEquals(
            "and what the cluster was last told has to be what the publisher believes it left",
            harness.indicators.mask,
            harness.btu.indicatorMasks.last()
        )
    }

    @Test
    fun `retiring the notification on the phone puts the bit out`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()
        harness.messageBus.post(whatsApp(key = "thread-1"))
        runCurrent()
        harness.btu.press(FunctionId.BACK)
        runCurrent()

        harness.messageBus.post(MessageNotificationEvent.Removed("thread-1"))
        runCurrent()

        assertEquals("nothing unread means nothing lit", 0, harness.indicators.mask)
        assertEquals(0, harness.btu.indicatorMasks.last())
    }

}
