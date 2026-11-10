package com.opensab.domain.message

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The reply is on the **event** and not on [MessageInfo], which stays a POJO compared by structural
 * equality — see [MessageReplyCommands] for the whole of that argument.
 */
class MessageNotificationEventTest {

    private val notification = MessageNotification(
        packageName = "com.whatsapp",
        key = "0|com.whatsapp|1|null|10123",
        title = "Elena",
        text = "Are you far?",
        conversationTitle = null,
        isGroupConversation = false,
        isGroupSummary = false,
        isOngoing = false,
        postTimeMillis = 1_700_000_000_000L
    )

    @Test
    fun `carries the reply action the notification published`() {
        val reply = RemoteInputReplyCommands(action = "reply") { _, _ -> true }

        assertSame(reply, MessageNotificationEvent.Posted(notification, reply).reply)
    }

    /**
     * Defaulted to null, and that default is the normal case and not a convenience: a grouped
     * summary, a muted thread and any app outside the thirteen publish no `RemoteInput` at all, and
     * the page must read that as "this one cannot be replied to" rather than as a missing wiring.
     */
    @Test
    fun `carries no reply when the notification published none`() {
        assertNull(MessageNotificationEvent.Posted(notification).reply)
    }
}
