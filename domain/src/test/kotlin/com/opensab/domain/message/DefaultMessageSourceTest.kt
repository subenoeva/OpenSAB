package com.opensab.domain.message

import kotlinx.coroutines.CoroutineScope
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
 * The row that matters most is `yieldScreen`: giving the screen back is not throwing the message
 * away. The message stays — and with it the bit the cluster lights from it, tested in
 * `MessageIndicatorTest` — and the carousel entry stays open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMessageSourceTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private class Harness(scope: CoroutineScope) {
        val bus = MessageNotificationBus()
        val source = DefaultMessageSource(bus)

        init {
            source.start(scope)
        }
    }

    private fun notification(
        key: String = "0|com.whatsapp|1|null|10123",
        title: String? = "Elena",
        text: String? = "Are you far?",
        packageName: String = "com.whatsapp",
        postTimeMillis: Long = 1_700_000_000_000L
    ) = MessageNotification(
        packageName = packageName,
        key = key,
        title = title,
        text = text,
        conversationTitle = null,
        isGroupConversation = false,
        isGroupSummary = false,
        isOngoing = false,
        postTimeMillis = postTimeMillis
    )

    private fun post(
        bus: MessageNotificationBus,
        notification: MessageNotification,
        reply: MessageReplyCommands? = null
    ) {
        bus.post(MessageNotificationEvent.Posted(notification, reply))
    }

    /** A reply handle that records what went through it, standing in for a live `RemoteInput`. */
    private class RecordingReply(private val name: String) : MessageReplyCommands {
        val sent = mutableListOf<String>()
        var answers = true

        override suspend fun reply(text: String): Boolean {
            sent += text
            return answers
        }

        override fun toString() = name
    }

    @Test
    fun aPostedMessageClaimsTheScreen() = testScope.runTest {
        val harness = Harness(backgroundScope)

        post(harness.bus, notification())
        runCurrent()

        assertEquals("Elena", harness.source.currentMessage.value?.sender)
        assertEquals("Are you far?", harness.source.currentMessage.value?.body)
        assertTrue("the message must claim the screen", harness.source.claimingScreen.value)
    }

    @Test
    fun yieldingTheScreenKeepsTheMessage() = testScope.runTest {
        val harness = Harness(backgroundScope)
        post(harness.bus, notification())
        runCurrent()

        harness.source.yieldScreen()
        runCurrent()

        assertFalse("the page must stop claiming the screen", harness.source.claimingScreen.value)
        assertEquals(
            "yielding gives back the screen, not the message",
            "Elena",
            harness.source.currentMessage.value?.sender
        )
    }

    @Test
    fun retiringTheNotificationClearsEverything() = testScope.runTest {
        val harness = Harness(backgroundScope)
        post(harness.bus, notification(key = "thread-1"))
        runCurrent()

        harness.bus.post(MessageNotificationEvent.Removed("thread-1"))
        runCurrent()

        assertNull(harness.source.currentMessage.value)
        assertFalse(harness.source.claimingScreen.value)
    }

    @Test
    fun retiringAnotherThreadChangesNothing() = testScope.runTest {
        val harness = Harness(backgroundScope)
        post(harness.bus, notification(key = "thread-1"))
        runCurrent()

        harness.bus.post(MessageNotificationEvent.Removed("thread-2"))
        runCurrent()

        assertEquals("Elena", harness.source.currentMessage.value?.sender)
    }

    /**
     * Many apps repost the same notification on every refresh of their own UI. That cannot take the
     * screen away from the rider twice.
     */
    @Test
    fun anIdenticalRepostDoesNotClaimTheScreenAgain() = testScope.runTest {
        val harness = Harness(backgroundScope)
        post(harness.bus, notification(key = "thread-1"))
        runCurrent()
        harness.source.yieldScreen()

        post(harness.bus, notification(key = "thread-1"))
        runCurrent()

        assertFalse("an identical repost is not a new message", harness.source.claimingScreen.value)
    }

    @Test
    fun aNewBodyOnTheSameThreadClaimsTheScreenAgain() = testScope.runTest {
        val harness = Harness(backgroundScope)
        post(harness.bus, notification(key = "thread-1"))
        runCurrent()
        harness.source.yieldScreen()

        post(harness.bus, notification(key = "thread-1", text = "I am at the door"))
        runCurrent()

        assertTrue("a second message in the thread is a new message", harness.source.claimingScreen.value)
        assertEquals("I am at the door", harness.source.currentMessage.value?.body)
    }

    @Test
    fun theListenerDisconnectingClearsEverything() = testScope.runTest {
        val harness = Harness(backgroundScope)
        post(harness.bus, notification())
        runCurrent()

        harness.bus.post(MessageNotificationEvent.ListenerDisconnected)
        runCurrent()

        assertNull(harness.source.currentMessage.value)
        assertFalse(harness.source.claimingScreen.value)
    }

    @Test
    fun stoppingClearsEverything() = testScope.runTest {
        val harness = Harness(backgroundScope)
        post(harness.bus, notification())
        runCurrent()

        harness.source.stop()
        runCurrent()

        assertNull(harness.source.currentMessage.value)
        assertFalse(harness.source.claimingScreen.value)
    }

    @Test
    fun aNotificationThePolicyRejectsChangesNothing() = testScope.runTest {
        val harness = Harness(backgroundScope)

        post(harness.bus, notification(packageName = "com.instagram.android"))
        runCurrent()

        assertNull(harness.source.currentMessage.value)
        assertFalse(harness.source.claimingScreen.value)
    }

    /**
     * The sources only live while a session does. Without a moto in front, the bus may publish
     * whatever it likes and nobody listens.
     */
    @Test
    fun nothingIsPublishedBeforeTheSessionStarts() = testScope.runTest {
        val bus = MessageNotificationBus()
        val source = DefaultMessageSource(bus)

        post(bus, notification())
        runCurrent()

        assertNull(source.currentMessage.value)
        assertFalse(source.claimingScreen.value)
    }

    // -------------------------------------------------------------------------------------------
    // The live reply handle
    // -------------------------------------------------------------------------------------------

    @Test
    fun `offers no reply before any message`() = testScope.runTest {
        val harness = Harness(backgroundScope)

        assertFalse("nothing can be replied to before a message arrives", harness.source.canReply.value)
    }

    @Test
    fun `offers a reply when the notification carries one`() = testScope.runTest {
        val harness = Harness(backgroundScope)

        post(harness.bus, notification(), RecordingReply("whatsapp"))
        runCurrent()

        assertTrue(harness.source.canReply.value)
    }

    /**
     * A grouped summary, a muted thread, an app that publishes no `RemoteInput`. The message is
     * still read out; what closes is the way back.
     */
    @Test
    fun `offers no reply when the app publishes none`() = testScope.runTest {
        val harness = Harness(backgroundScope)

        post(harness.bus, notification(), reply = null)
        runCurrent()

        assertEquals("the message still arrives", "Elena", harness.source.currentMessage.value?.sender)
        assertFalse("with no remote input there is no way back", harness.source.canReply.value)
    }

    /**
     * The handle belongs to the **notification**, not to the thread, and every repost brings a new
     * one — the old `PendingIntent` may already have been cancelled. Replying provokes a repost, so
     * this is the path the second reply of a conversation takes.
     */
    @Test
    fun `refreshes the commands on every repost`() = testScope.runTest {
        val harness = Harness(backgroundScope)
        val first = RecordingReply("first")
        val second = RecordingReply("second")
        post(harness.bus, notification(key = "thread-1"), first)
        runCurrent()

        post(harness.bus, notification(key = "thread-1", text = "I am at the door"), second)
        runCurrent()
        harness.source.reply("On my way")

        assertEquals(emptyList<String>(), first.sent)
        assertEquals(listOf("On my way"), second.sent)
    }

    /**
     * Even a repost the dedup drops brings a live handle, and the stale one has to go: the rider
     * has been looking at the same message all along and the button must still work.
     */
    @Test
    fun `refreshes the commands on a repost the dedup drops`() = testScope.runTest {
        val harness = Harness(backgroundScope)
        val first = RecordingReply("first")
        val second = RecordingReply("second")
        post(harness.bus, notification(key = "thread-1"), first)
        runCurrent()
        harness.source.yieldScreen()

        post(harness.bus, notification(key = "thread-1"), second)
        runCurrent()
        harness.source.reply("On my way")

        assertFalse("an identical repost is still not a new message", harness.source.claimingScreen.value)
        assertEquals(emptyList<String>(), first.sent)
        assertEquals(listOf("On my way"), second.sent)
    }

    @Test
    fun `stops offering a reply when the thread is retired`() = testScope.runTest {
        val harness = Harness(backgroundScope)
        post(harness.bus, notification(key = "thread-1"), RecordingReply("whatsapp"))
        runCurrent()

        harness.bus.post(MessageNotificationEvent.Removed("thread-1"))
        runCurrent()

        assertFalse(harness.source.canReply.value)
    }

    @Test
    fun `stops offering a reply when the listener disconnects`() = testScope.runTest {
        val harness = Harness(backgroundScope)
        post(harness.bus, notification(), RecordingReply("whatsapp"))
        runCurrent()

        harness.bus.post(MessageNotificationEvent.ListenerDisconnected)
        runCurrent()

        assertFalse(harness.source.canReply.value)
    }

    @Test
    fun `routes the reply to the commands of the live notification`() = testScope.runTest {
        val harness = Harness(backgroundScope)
        val reply = RecordingReply("whatsapp")
        post(harness.bus, notification(), reply)
        runCurrent()

        val answered = harness.source.reply("On my way")

        assertTrue(answered)
        assertEquals(listOf("On my way"), reply.sent)
    }

    /** A send the app refused is reported as one: the page paints `NotSent` and offers the retry. */
    @Test
    fun `reports a reply the app refused`() = testScope.runTest {
        val harness = Harness(backgroundScope)
        val reply = RecordingReply("whatsapp").apply { answers = false }
        post(harness.bus, notification(), reply)
        runCurrent()

        assertFalse(harness.source.reply("On my way"))
    }

    @Test
    fun `answers false when there is nothing to reply through`() = testScope.runTest {
        val harness = Harness(backgroundScope)

        assertFalse(harness.source.reply("On my way"))
    }

    /**
     * Yielding gives back the screen and keeps the message, so it keeps the way back too: the rider
     * comes to the carousel entry and opens the reading again with the button still lit.
     */
    @Test
    fun `keeps the reply through a yield`() = testScope.runTest {
        val harness = Harness(backgroundScope)
        post(harness.bus, notification(), RecordingReply("whatsapp"))
        runCurrent()

        harness.source.yieldScreen()
        runCurrent()

        assertTrue(harness.source.canReply.value)
    }
}
