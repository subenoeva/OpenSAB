package com.opensab.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a notification has to be before it is worth taking the screen away from the rider.
 *
 * Returning null is a first-class answer here: most of what a messaging app posts is noise, and the
 * cluster is the worst possible place to show it.
 */
class MessageNotificationParserTest {

    private fun notification(
        packageName: String = "com.whatsapp",
        key: String = "0|com.whatsapp|1|null|10123",
        title: String? = "Elena",
        text: String? = "Are you far?",
        conversationTitle: String? = null,
        isGroupConversation: Boolean = false,
        isGroupSummary: Boolean = false,
        isOngoing: Boolean = false,
        isForegroundService: Boolean = false,
        postTimeMillis: Long = 1_700_000_000_000L,
        messages: List<HistoricMessage> = emptyList()
    ) = MessageNotification(
        packageName = packageName,
        key = key,
        title = title,
        text = text,
        conversationTitle = conversationTitle,
        isGroupConversation = isGroupConversation,
        isGroupSummary = isGroupSummary,
        isOngoing = isOngoing,
        isForegroundService = isForegroundService,
        postTimeMillis = postTimeMillis,
        messages = messages
    )

    @Test
    fun rejectsAPackageOutsideTheAllowlist() {
        val parsed = MessageNotificationParser.parse(notification(packageName = "com.instagram.android"))

        assertNull(parsed)
    }

    /** The stacked "3 new messages" summary has no body of its own; only its children do. */
    @Test
    fun rejectsTheGroupSummary() {
        assertNull(MessageNotificationParser.parse(notification(isGroupSummary = true)))
    }

    /** Chat heads and "backup in progress" are ongoing notifications, not messages. */
    @Test
    fun rejectsAnOngoingNotification() {
        assertNull(MessageNotificationParser.parse(notification(isOngoing = true)))
    }

    /**
     * WhatsApp's `GcmFGService` posts "Checking for new messages" titled "WhatsApp" while it syncs.
     * Since Android 14 a foreground service's notification can arrive without `FLAG_ONGOING_EVENT`;
     * on the 2026-09-30 ride it took the cluster three times, for about 3 s each.
     */
    @Test
    fun rejectsAForegroundServiceNotificationThatIsNotMarkedOngoing() {
        val parsed = MessageNotificationParser.parse(
            notification(
                key = "0|com.whatsapp|11|null|10271",
                title = "WhatsApp",
                text = "Checking for new messages",
                isOngoing = false,
                isForegroundService = true
            )
        )

        assertNull(parsed)
    }

    /**
     * A `MessagingStyle` entry with no `Person` is the rider's own message. If the last one is
     * theirs they just answered from the phone, and there is nothing left to read out.
     */
    @Test
    fun rejectsANotificationWhoseLastMessageIsTheRidersOwn() {
        val parsed = MessageNotificationParser.parse(
            notification(
                messages = listOf(
                    HistoricMessage("Elena", "Are you far?", 1_700_000_000_000L),
                    HistoricMessage(null, "Ten minutes", 1_700_000_060_000L)
                )
            )
        )

        assertNull(parsed)
    }

    @Test
    fun rejectsABlankBody() {
        assertNull(MessageNotificationParser.parse(notification(text = "   ")))
        assertNull(MessageNotificationParser.parse(notification(text = null)))
    }

    @Test
    fun readsSenderAndBodyOutOfAPlainNotification() {
        val parsed = MessageNotificationParser.parse(notification())

        assertNotNull(parsed)
        assertEquals("Elena", parsed!!.sender)
        assertEquals("Are you far?", parsed.body)
    }

    @Test
    fun prefersTheLastMessagingStyleEntryOverTheFlatText() {
        val parsed = MessageNotificationParser.parse(
            notification(
                text = "2 new messages",
                messages = listOf(
                    HistoricMessage("Elena", "Are you far?", 1_700_000_000_000L),
                    HistoricMessage("Elena", "I am at the door", 1_700_000_060_000L)
                )
            )
        )

        assertEquals("Elena", parsed!!.sender)
        assertEquals("I am at the door", parsed.body)
    }

    /**
     * In a group the rider's first question is which group, not which person, and the cluster has
     * two short lines.
     */
    @Test
    fun namesTheGroupAndPrefixesTheBodyWithThePerson() {
        val parsed = MessageNotificationParser.parse(
            notification(
                packageName = "org.telegram.messenger",
                conversationTitle = "Sunday ride",
                isGroupConversation = true,
                messages = listOf(HistoricMessage("Marcos", "Fuel stop in 20", 1_700_000_060_000L))
            )
        )

        assertEquals("Sunday ride", parsed!!.sender)
        assertEquals("Marcos: Fuel stop in 20", parsed.body)
    }

    @Test
    fun fallsBackToTheConversationTitleWhenTheEntryHasNoPerson() {
        val parsed = MessageNotificationParser.parse(
            notification(
                title = null,
                conversationTitle = "Elena",
                messages = emptyList()
            )
        )

        assertEquals("Elena", parsed!!.sender)
    }

    @Test
    fun takesTheTimestampOfTheLastEntryWhenThereIsOne() {
        val parsed = MessageNotificationParser.parse(
            notification(
                postTimeMillis = 1_700_000_000_000L,
                messages = listOf(HistoricMessage("Elena", "I am at the door", 1_700_000_060_000L))
            )
        )

        assertEquals(1_700_000_060_000L, parsed!!.timestamp)
    }

    @Test
    fun fallsBackToThePostTimeWhenThereIsNoEntry() {
        val parsed = MessageNotificationParser.parse(notification(postTimeMillis = 1_700_000_000_000L))

        assertEquals(1_700_000_000_000L, parsed!!.timestamp)
    }

    @Test
    fun labelsTheAppTheMessageCameFrom() {
        val parsed = MessageNotificationParser.parse(
            notification(packageName = "org.thoughtcrime.securesms")
        )

        assertEquals("Signal", parsed!!.appName)
    }

    @Test
    fun trimsTheBodyAndTheSender() {
        val parsed = MessageNotificationParser.parse(
            notification(title = "  Elena  ", text = "  Are you far?  ")
        )

        assertEquals("Elena", parsed!!.sender)
        assertEquals("Are you far?", parsed.body)
    }
}
