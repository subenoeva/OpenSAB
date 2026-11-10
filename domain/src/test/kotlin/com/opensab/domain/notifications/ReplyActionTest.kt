package com.opensab.domain.notifications

import com.opensab.domain.notifications.NotificationAction.Companion.SEMANTIC_ACTION_MARK_AS_READ
import com.opensab.domain.notifications.NotificationAction.Companion.SEMANTIC_ACTION_NONE
import com.opensab.domain.notifications.NotificationAction.Companion.SEMANTIC_ACTION_REPLY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which action a reply is sent through. Replying belongs to the notification, not the package: one
 * action that takes free-form input and has an intent to fire, and among those the one the app
 * marked `SEMANTIC_ACTION_REPLY`, wherever it sits.
 */
class ReplyActionTest {

    private fun whatsApp(vararg actions: NotificationAction<String>) = ListenedNotification<Any, String>(
        key = "0|com.whatsapp|1|abc@s.whatsapp.net|10150",
        packageName = "com.whatsapp",
        actions = actions.toList()
    )

    private fun action(
        handle: String,
        semantic: Int = SEMANTIC_ACTION_NONE,
        freeForm: Boolean = false,
        hasIntent: Boolean = true
    ) = NotificationAction(handle, semantic, acceptsFreeFormInput = freeForm, hasIntent = hasIntent)

    @Test
    fun theReplySemanticActionWinsEvenWhenItIsNotTheFirstCandidate() {
        val notification = whatsApp(
            action("mark-as-read", semantic = SEMANTIC_ACTION_MARK_AS_READ),
            action("comment", freeForm = true),
            action("reply", semantic = SEMANTIC_ACTION_REPLY, freeForm = true)
        )

        assertEquals("reply", notification.replyAction())
    }

    @Test
    fun withoutAReplySemanticTheFirstFreeFormActionIsUsed() {
        val notification = whatsApp(
            action("mark-as-read", semantic = SEMANTIC_ACTION_MARK_AS_READ),
            action("first", freeForm = true),
            action("second", freeForm = true)
        )

        assertEquals("first", notification.replyAction())
    }

    @Test
    fun ofTwoReplySemanticActionsTheFirstWins() {
        val notification = whatsApp(
            action("first", semantic = SEMANTIC_ACTION_REPLY, freeForm = true),
            action("second", semantic = SEMANTIC_ACTION_REPLY, freeForm = true)
        )

        assertEquals("first", notification.replyAction())
    }

    /** A reply the app marked but cannot fire is no reply: the text would go nowhere. */
    @Test
    fun aReplyActionWithoutAnIntentIsSkipped() {
        val notification = whatsApp(
            action("dead-reply", semantic = SEMANTIC_ACTION_REPLY, freeForm = true, hasIntent = false),
            action("comment", freeForm = true)
        )

        assertEquals("comment", notification.replyAction())
    }

    /** Choice-only input (canned answers) cannot carry what the rider dictates. */
    @Test
    fun aReplyActionWithoutFreeFormInputIsSkipped() {
        val notification = whatsApp(action("canned", semantic = SEMANTIC_ACTION_REPLY, freeForm = false))

        assertNull(notification.replyAction())
    }

    /** A grouped summary or a muted thread publishes no action at all. */
    @Test
    fun aNotificationWithoutActionsHasNoReplyAction() {
        assertNull(whatsApp().replyAction())
    }
}
