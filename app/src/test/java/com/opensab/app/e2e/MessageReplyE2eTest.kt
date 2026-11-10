package com.opensab.app.e2e

import com.opensab.domain.audio.Cue
import com.opensab.domain.message.MessageNotification
import com.opensab.domain.message.MessageNotificationEvent
import com.opensab.domain.message.MessageReplyCommands
import com.opensab.sab.protocol.assignment.FunctionId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The whole way round, over the simulated cable and the graph Hilt builds: a message arrives, the
 * cluster asks, the rider says yes and hears it, presses right, hears the beep, dictates, hears his
 * own words read back, presses right again — and the text reaches the notification's `RemoteInput`.
 *
 * What no test here can reach is the last inch: firing the real `PendingIntent`.
 *
 * `runCurrent()` and `advanceTimeBy()` throughout, never `advanceUntilIdle()`: it does not run
 * `backgroundScope` work and leaves the virtual clock at 0.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageReplyE2eTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    /** The other phone, standing in for the app's own `RemoteInput`. */
    private class RecordingReply : MessageReplyCommands {
        val received = mutableListOf<String>()
        var accepts = true

        override suspend fun reply(text: String): Boolean {
            received += text
            return accepts
        }
    }

    private fun whatsApp(reply: MessageReplyCommands?) = MessageNotificationEvent.Posted(
        notification = MessageNotification(
            packageName = "com.whatsapp",
            key = "0|com.whatsapp|1|null|10123",
            title = "Elena",
            text = "Are you far",
            conversationTitle = null,
            isGroupConversation = false,
            isGroupSummary = false,
            isOngoing = false,
            postTimeMillis = 1_700_000_000_000L
        ),
        reply = reply
    )

    /** Message arrived, question answered, body read. */
    private suspend fun TestScope.onTheReading(
        reply: MessageReplyCommands?
    ): SessionHarness {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()
        harness.messageBus.post(whatsApp(reply))
        runCurrent()
        harness.btu.press(FunctionId.SELECT)
        runCurrent()
        return harness
    }

    @Test
    fun `the rider dictates a reply and it reaches the notification`() = testScope.runTest {
        val phone = RecordingReply()
        val harness = onTheReading(phone)
        harness.recognizer.willHear("Almost there")
        val heardBeforeDictating = harness.spoken.utterances.size

        // Right on the reading opens the dictation, which cues LISTEN and listens.
        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        assertEquals("MessageReplyPage", harness.engine.stack.peek()?.name)
        assertTrue(
            "the LISTEN cue has to sound before the microphone opens",
            harness.cuePlayer.played.contains(Cue.LISTEN)
        )
        assertEquals(1, harness.recognizer.listenCalls)

        val readBack = harness.spoken.utterances.drop(heardBeforeDictating)
        assertEquals("what he said is read back to him, once", 1, readBack.size)
        assertTrue(readBack.single().text.contains("Almost there"))
        assertTrue(
            "and the cluster shows it",
            harness.btu.baseFrames().last().payload.containsAscii("Almost there")
        )

        // Right again sends it.
        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        assertEquals(listOf("Almost there"), phone.received)
        assertTrue(
            "and the cluster says so",
            harness.btu.baseFrames().last().payload.containsAscii("Sent")
        )
    }

    /**
     * And then it gives the screen back on its own, after the seconds the rider chose.
     *
     * What it lands on is the **carousel** and not the reading underneath, and that is the yield
     * doing its work rather than the pop: giving the screen back withdraws the message's claim, so
     * the whole interrupt stack goes with it — the question, the reading and the dictation. The
     * message itself stays unread and the MESSAGE bit stays lit, because only the phone retiring
     * the notification clears those, and replying is exactly what makes a messaging
     * app retire it.
     */
    @Test
    fun `the cluster holds Sent for as long as the rider asked and then yields`() = testScope.runTest {
        val harness = onTheReading(RecordingReply())
        harness.sentLinger.value = 7.seconds
        harness.recognizer.willHear("Almost there")
        harness.btu.press(FunctionId.SELECT)
        runCurrent()
        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals("MessageReplyPage", harness.engine.stack.peek()?.name)

        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(
            "the page gives the screen back once the reply is away",
            "RootMenu",
            harness.engine.stack.peek()?.name
        )
    }

    /**
     * An app that publishes no `RemoteInput` leaves the reading with no button, and a press that
     * goes nowhere.
     */
    @Test
    fun `a notification with no remote input leaves the reading as it was`() = testScope.runTest {
        val harness = onTheReading(reply = null)

        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        assertEquals(
            "there is nothing to dictate into",
            "MessagePage",
            harness.engine.stack.peek()?.name
        )
        assertTrue(
            "and the body is still what the cluster shows",
            harness.btu.baseFrames().last().payload.containsAscii("Are you far")
        )
    }

    /** Nothing says "sent" that was not: a refused send offers the retry instead. */
    @Test
    fun `a refused send says so and keeps the page`() = testScope.runTest {
        val phone = RecordingReply().apply { accepts = false }
        val harness = onTheReading(phone)
        harness.recognizer.willHear("Almost there")
        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        harness.btu.press(FunctionId.SELECT)
        runCurrent()
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals("MessageReplyPage", harness.engine.stack.peek()?.name)
        val painted = harness.btu.baseFrames().last().payload
        assertTrue(painted.containsAscii("Not sent"))
        assertFalse("nothing may say Sent that was not", painted.containsAscii("Sent "))
    }

    /**
     * A call arriving mid-dictation closes the microphone, and nothing in the engine or the page
     * says a word about closing it: the call claims the screen, the page stops being mounted, and
     * the work job dies with it.
     */
    @Test
    fun `an incoming call mid-dictation closes the microphone`() = testScope.runTest {
        val harness = onTheReading(RecordingReply())
        harness.recognizer.listensUntilReleased = true
        harness.btu.press(FunctionId.SELECT)
        runCurrent()
        assertEquals("MessageReplyPage", harness.engine.stack.peek()?.name)

        harness.callDriver.post(callerName = "Carlos")
        runCurrent()

        assertEquals("CallPage", harness.engine.stack.peek()?.name)
        harness.recognizer.release("said to nobody")
        runCurrent()
        assertEquals(
            "a listen cut by a call must not come back and send something",
            "CallPage",
            harness.engine.stack.peek()?.name
        )
    }
}
