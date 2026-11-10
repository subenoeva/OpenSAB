package com.opensab.domain.notifications

import com.opensab.domain.call.CallNotification
import com.opensab.domain.message.HistoricMessage
import com.opensab.domain.message.MessageNotification
import com.opensab.domain.nav.NavigationNotification
import com.opensab.domain.notifications.ListenedNotification.Companion.EXTRA_CALL_TYPE
import com.opensab.domain.notifications.ListenedNotification.Companion.EXTRA_PROGRESS
import com.opensab.domain.notifications.ListenedNotification.Companion.EXTRA_PROGRESS_MAX
import com.opensab.domain.notifications.ListenedNotification.Companion.EXTRA_SHORT_CRITICAL_TEXT
import com.opensab.domain.notifications.ListenedNotification.Companion.EXTRA_SUB_TEXT
import com.opensab.domain.notifications.ListenedNotification.Companion.EXTRA_TEXT
import com.opensab.domain.notifications.ListenedNotification.Companion.EXTRA_TITLE
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The projections the listener used to build straight from a `StatusBarNotification`. The shapes
 * are the ones the phone posted in the field: Google Dialer and Teams `CallStyle`, a WhatsApp
 * `MessagingStyle`, and the Maps `ProgressStyle` as dumped on Maps 26.38.
 */
class ListenedNotificationTest {

    /** Dialer, ringing, an unknown number: the title comes wrapped in U+202A/U+202C, as captured. */
    @Test
    fun aRingingDialerCallWithNoPersonProjectsNullPersonFields() {
        val listened = ListenedNotification<Any, Any>(
            key = "0|com.google.android.dialer|1|null|10133",
            packageName = "com.google.android.dialer",
            category = "call",
            template = CALL_STYLE,
            texts = mapOf(EXTRA_TITLE to "‪600 00 00 00‬"),
            ints = mapOf(EXTRA_CALL_TYPE to 1)
        )

        assertEquals(
            CallNotification(
                key = "0|com.google.android.dialer|1|null|10133",
                packageName = "com.google.android.dialer",
                category = "call",
                template = CALL_STYLE,
                callType = 1,
                title = "‪600 00 00 00‬",
                personName = null,
                personUri = null
            ),
            listened.toCallNotification()
        )
    }

    @Test
    fun anOngoingDialerCallCarriesItsPerson() {
        val listened = ListenedNotification<Any, Any>(
            key = "0|com.google.android.dialer|1|null|10133",
            packageName = "com.google.android.dialer",
            category = "call",
            template = CALL_STYLE,
            texts = mapOf(EXTRA_TITLE to "Mama"),
            ints = mapOf(EXTRA_CALL_TYPE to 2),
            callPerson = NotificationPerson(name = "Mama", uri = "tel:+34600000000")
        )

        val call = listened.toCallNotification()

        assertEquals(2, call.callType)
        assertEquals("Mama", call.personName)
        assertEquals("tel:+34600000000", call.personUri)
    }

    /** A notification with no `android.callType` extra has none: 0 would read as a call state. */
    @Test
    fun aCallWithoutCallTypeProjectsANullCallType() {
        val listened = ListenedNotification<Any, Any>(
            key = "0|com.microsoft.teams|7|null|10200",
            packageName = "com.microsoft.teams",
            category = "call",
            template = CALL_STYLE
        )

        assertEquals(null, listened.toCallNotification().callType)
    }

    @Test
    fun aWhatsAppGroupMessageProjectsItsConversationAndFlags() {
        val history = listOf(
            HistoricMessage(sender = "Tania", text = "¿Llegas?", timestampMillis = 1_789_148_700_000),
            HistoricMessage(sender = null, text = "En 10 min", timestampMillis = 1_789_148_720_000)
        )
        val listened = ListenedNotification<Any, Any>(
            key = "0|com.whatsapp|1|abc@g.us|10150",
            packageName = "com.whatsapp",
            postTimeMillis = 1_789_148_722_939,
            category = "msg",
            template = "android.app.Notification\$MessagingStyle",
            isGroupSummary = false,
            isOngoing = false,
            texts = mapOf(EXTRA_TITLE to "Familia: Tania", EXTRA_TEXT to "¿Llegas?"),
            conversationTitle = "Familia",
            isGroupConversation = true,
            messages = history
        )

        assertEquals(
            MessageNotification(
                packageName = "com.whatsapp",
                key = "0|com.whatsapp|1|abc@g.us|10150",
                title = "Familia: Tania",
                text = "¿Llegas?",
                conversationTitle = "Familia",
                isGroupConversation = true,
                isGroupSummary = false,
                isOngoing = false,
                postTimeMillis = 1_789_148_722_939,
                messages = history
            ),
            listened.toMessageNotification()
        )
    }

    @Test
    fun aTelegramGroupSummaryKeepsItsFlags() {
        val listened = ListenedNotification<Any, Any>(
            key = "0|org.telegram.messenger|0|summary|10160",
            packageName = "org.telegram.messenger",
            isGroupSummary = true,
            isOngoing = true
        )

        val message = listened.toMessageNotification()

        assertEquals(true, message.isGroupSummary)
        assertEquals(true, message.isOngoing)
        assertEquals(null, message.conversationTitle)
        assertEquals(false, message.isGroupConversation)
        assertEquals(emptyList<HistoricMessage>(), message.messages)
    }

    @Test
    fun aForegroundServiceNotificationKeepsItsFlag() {
        val listened = ListenedNotification<Any, Any>(
            key = "0|com.whatsapp|11|null|10271",
            packageName = "com.whatsapp",
            isForegroundService = true
        )

        assertEquals(true, listened.toMessageNotification().isForegroundService)
    }

    /** Each extra to its own field, nothing merged, the arrow name alongside. */
    @Test
    fun aMapsNotificationProjectsEveryExtraToItsOwnField() {
        val listened = ListenedNotification<Any, Any>(
            key = "0|com.google.android.apps.maps|1|null|10170",
            packageName = "com.google.android.apps.maps",
            category = "navigation",
            texts = mapOf(
                EXTRA_TITLE to "90 m · Gira a la izquierda hacia Travesía Montecastaños",
                EXTRA_SUB_TEXT to "Llegada: 13:10",
                EXTRA_SHORT_CRITICAL_TEXT to ""
            ),
            ints = mapOf(EXTRA_PROGRESS to 12, EXTRA_PROGRESS_MAX to 2237)
        )

        assertEquals(
            NavigationNotification(
                packageName = "com.google.android.apps.maps",
                arrow = "maneuver_turn_normal_left",
                title = "90 m · Gira a la izquierda hacia Travesía Montecastaños",
                text = null,
                subText = "Llegada: 13:10",
                progress = 12,
                progressMax = 2237,
                shortCriticalText = ""
            ),
            listened.toNavigationNotification(arrow = "maneuver_turn_normal_left")
        )
    }

    @Test
    fun theRouteIsTheRoutersForTheNotificationsOwnFields() {
        val whatsAppCall = ListenedNotification<Any, Any>(
            key = "k",
            packageName = "com.whatsapp",
            category = "call",
            template = CALL_STYLE
        )

        assertEquals(NotificationRoute.CALL, whatsAppCall.route)
        assertEquals(NotificationRoute.MESSAGE, whatsAppCall.copy(category = "msg", template = null).route)
    }

    private companion object {
        const val CALL_STYLE = "android.app.Notification\$CallStyle"
    }
}
