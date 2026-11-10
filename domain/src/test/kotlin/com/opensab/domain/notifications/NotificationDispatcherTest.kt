package com.opensab.domain.notifications

import com.opensab.domain.call.CallNotificationBus
import com.opensab.domain.call.CallNotificationEvent
import com.opensab.domain.message.MessageNotificationBus
import com.opensab.domain.message.MessageNotificationEvent
import com.opensab.domain.nav.ArrowBits
import com.opensab.domain.nav.ArrowMatch
import com.opensab.domain.nav.ArrowRecognition
import com.opensab.domain.nav.ArrowRecognizer
import com.opensab.domain.nav.ManeuverType
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.nav.NavigationNotificationBus
import com.opensab.domain.notifications.ListenedNotification.Companion.EXTRA_CALL_TYPE
import com.opensab.domain.notifications.ListenedNotification.Companion.EXTRA_SUB_TEXT
import com.opensab.domain.notifications.ListenedNotification.Companion.EXTRA_TEXT
import com.opensab.domain.notifications.ListenedNotification.Companion.EXTRA_TITLE
import com.opensab.domain.notifications.NotificationAction.Companion.SEMANTIC_ACTION_MARK_AS_READ
import com.opensab.domain.notifications.NotificationAction.Companion.SEMANTIC_ACTION_REPLY
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the listener does with each notification, on the real buses. Packages and extras are the
 * ones the phone posted in the field (Google Dialer, Teams, WhatsApp, Telegram, Maps, Firefox), and the route rules are `NotificationRouter`'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationDispatcherTest {

    private val callBus = CallNotificationBus()
    private val messageBus = MessageNotificationBus()
    private val navigationBus = NavigationNotificationBus()

    private val sentIntents = mutableListOf<String>()
    private val sentReplies = mutableListOf<Triple<String, String, String>>()

    private val departArrow = ArrowBits.of(4, 4, IntArray(16) { if (it % 4 == 1) OPAQUE else 0 })!!
    private val unknownArrow = ArrowBits.of(4, 4, IntArray(16) { if (it < 4) OPAQUE else 0 })!!

    private var intercept = true

    private val dispatcher = NotificationDispatcher<String, String>(
        callBus = callBus,
        messageBus = messageBus,
        navigationBus = navigationBus,
        arrows = ArrowRecognizer { mapOf("maneuver_depart" to departArrow) },
        interceptNavigation = { intercept },
        sendIntent = { sentIntents += it },
        sendReply = { packageName, action, text -> sentReplies += Triple(packageName, action, text); true }
    )

    private class Collected(
        val calls: MutableList<CallNotificationEvent> = mutableListOf(),
        val messages: MutableList<MessageNotificationEvent> = mutableListOf(),
        val instructions: MutableList<NavInstruction?> = mutableListOf()
    )

    private fun TestScope.collectAll(): Collected {
        val collected = Collected()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { callBus.events.collect { collected.calls += it } }
        backgroundScope.launch(dispatcher) { messageBus.events.collect { collected.messages += it } }
        backgroundScope.launch(dispatcher) { navigationBus.instructions.collect { collected.instructions += it } }
        return collected
    }

    private var arrowDraws = 0
    private fun drawing(bits: ArrowBits?): () -> ArrowBits? = {
        arrowDraws++
        bits
    }

    private fun dialer(callType: Int) = ListenedNotification<String, String>(
        key = DIALER_KEY,
        packageName = "com.google.android.dialer",
        category = "call",
        template = CALL_STYLE,
        texts = mapOf(EXTRA_TITLE to "Mama"),
        ints = mapOf(EXTRA_CALL_TYPE to callType),
        answerIntent = "answer",
        declineIntent = "decline",
        hangUpIntent = "hang-up"
    )

    private fun whatsAppMessage() = ListenedNotification<String, String>(
        key = WHATSAPP_KEY,
        packageName = "com.whatsapp",
        category = "msg",
        template = "android.app.Notification\$MessagingStyle",
        texts = mapOf(EXTRA_TITLE to "Tania", EXTRA_TEXT to "¿Llegas?"),
        actions = listOf(
            NotificationAction("mark-as-read", SEMANTIC_ACTION_MARK_AS_READ, acceptsFreeFormInput = false, hasIntent = true),
            NotificationAction("reply", SEMANTIC_ACTION_REPLY, acceptsFreeFormInput = true, hasIntent = true)
        )
    )

    private fun maps(category: String? = "navigation", title: String = "Dirígete hacia Rúa do Castro") =
        ListenedNotification<String, String>(
            key = MAPS_KEY,
            packageName = "com.google.android.apps.maps",
            category = category,
            texts = mapOf(EXTRA_TITLE to title, EXTRA_SUB_TEXT to "Llegada: 13:10")
        )

    @Test
    fun aMapsNotificationIsDroppedAndItsArrowNeverDrawnWhileInterceptionIsOff() = runTest {
        val collected = collectAll()
        intercept = false

        val outcome = dispatcher.onPosted(maps(), drawing(departArrow))

        assertEquals(PostedOutcome.Ignored, outcome)
        assertEquals(0, arrowDraws)
        assertEquals(listOf<NavInstruction?>(null), collected.instructions)
    }

    @Test
    fun turningInterceptionOffMidRouteRetiresTheManeuver() = runTest {
        val collected = collectAll()
        dispatcher.onPosted(maps(), drawing(departArrow))
        intercept = false

        dispatcher.onPosted(maps(title = "Gira a la derecha"), drawing(departArrow))

        assertNull(collected.instructions.last())
    }

    @Test
    fun aRingingDialerCallIsPostedWithCommandsThatFireItsOwnIntents() = runTest {
        val collected = collectAll()

        val outcome = dispatcher.onPosted(dialer(callType = 1), drawing(null))

        val posted = collected.calls.single() as CallNotificationEvent.Posted
        assertEquals(dialer(1).toCallNotification(), posted.notification)
        assertEquals(PostedOutcome.Call(dialer(1).toCallNotification()), outcome)
        posted.commands.answer()
        posted.commands.reject()
        posted.commands.disconnect()
        assertEquals(listOf("answer", "decline", "hang-up"), sentIntents)
        assertEquals(emptyList<MessageNotificationEvent>(), collected.messages)
    }

    /** `NotificationRouter`: CALL before MESSAGE, or a WhatsApp call reads as a message. */
    @Test
    fun aWhatsAppCallGoesToTheCallBusAndNotToTheMessageBus() = runTest {
        val collected = collectAll()

        dispatcher.onPosted(dialer(1).copy(key = WHATSAPP_KEY, packageName = "com.whatsapp"), drawing(null))

        assertTrue(collected.calls.single() is CallNotificationEvent.Posted)
        assertEquals(emptyList<MessageNotificationEvent>(), collected.messages)
    }

    /**
     * Every notification that is not a call retires its key from the call bus: Google Dialer answers
     * a rejection by re-posting the same key without `CallStyle` (`CallNotificationEvent.Removed`).
     */
    @Test
    fun aDialerRepostWithoutCallStyleRetiresTheCall() = runTest {
        val collected = collectAll()
        dispatcher.onPosted(dialer(1), drawing(null))

        val outcome = dispatcher.onPosted(dialer(1).copy(category = "status", template = null), drawing(null))

        assertEquals(PostedOutcome.Ignored, outcome)
        assertEquals(CallNotificationEvent.Removed(DIALER_KEY), collected.calls.last())
    }

    @Test
    fun aWhatsAppMessageIsPostedWithItsReplyThroughTheReplyAction() = runTest {
        val collected = collectAll()

        val outcome = dispatcher.onPosted(whatsAppMessage(), drawing(null))

        assertEquals(PostedOutcome.Message, outcome)
        assertEquals(listOf(CallNotificationEvent.Removed(WHATSAPP_KEY)), collected.calls)
        val posted = collected.messages.single() as MessageNotificationEvent.Posted
        assertEquals(whatsAppMessage().toMessageNotification(), posted.notification)
        assertEquals(true, posted.reply!!.reply("En 10 min"))
        assertEquals(listOf(Triple("com.whatsapp", "reply", "En 10 min")), sentReplies)
    }

    /** No reply action is still a reply handle: it answers `false`, and the page offers the retry. */
    @Test
    fun aMessageWithoutReplyActionRepliesNotSent() = runTest {
        val collected = collectAll()

        dispatcher.onPosted(whatsAppMessage().copy(actions = emptyList()), drawing(null))

        val posted = collected.messages.single() as MessageNotificationEvent.Posted
        assertEquals(false, posted.reply!!.reply("En 10 min"))
        assertEquals(emptyList<Triple<String, String, String>>(), sentReplies)
    }

    @Test
    fun aMapsNavigationNotificationPostsTheManeuverReadFromItsArrow() = runTest {
        val collected = collectAll()

        val outcome = dispatcher.onPosted(maps(), drawing(departArrow))

        val instruction = collected.instructions.single()!!
        assertEquals(ManeuverType.DEPART, instruction.maneuver)
        assertEquals("Dirígete hacia Rúa do Castro", instruction.title)
        assertEquals("13:10", instruction.etaFormatted)
        assertEquals(PostedOutcome.Navigation(ArrowRecognition.Known(ArrowMatch("maneuver_depart", 0)), instruction), outcome)
        assertEquals(1, arrowDraws)
        assertEquals(listOf(CallNotificationEvent.Removed(MAPS_KEY)), collected.calls)
    }

    @Test
    fun anUnknownArrowStillPostsTheInstructionAsUnknownManeuver() = runTest {
        val collected = collectAll()

        val outcome = dispatcher.onPosted(maps(), drawing(unknownArrow)) as PostedOutcome.Navigation

        assertEquals(ManeuverType.UNKNOWN, collected.instructions.single()!!.maneuver)
        assertTrue(outcome.arrow is ArrowRecognition.Unknown)
    }

    /**
     * Maps is navigation only with `category=navigation`. Its other notifications
     * (location sharing, traffic) are ignored, and the package alone never decides.
     */
    @Test
    fun aMapsNotificationWithoutNavigationCategoryIsIgnoredAndItsArrowNeverDrawn() = runTest {
        val collected = collectAll()

        val outcome = dispatcher.onPosted(maps(category = null), drawing(departArrow))

        assertEquals(PostedOutcome.Ignored, outcome)
        assertEquals(emptyList<NavInstruction?>(), collected.instructions)
        assertEquals(0, arrowDraws)
    }

    /** The category alone does not decide either: Waze has no reader. */
    @Test
    fun aNavigationCategoryFromAnAppWithoutReaderIsIgnored() = runTest {
        val collected = collectAll()

        val outcome = dispatcher.onPosted(maps().copy(packageName = "com.waze"), drawing(departArrow))

        assertEquals(PostedOutcome.Ignored, outcome)
        assertEquals(emptyList<NavInstruction?>(), collected.instructions)
        assertEquals(0, arrowDraws)
    }

    /**
     * A navigation notification the reader cannot read posts nothing, so the cluster keeps the
     * previous maneuver; the outcome says so, for the listener to leave a trace.
     */
    @Test
    fun anUnreadableNavigationNotificationPostsNothingAndSaysSo() = runTest {
        val collected = collectAll()

        val outcome = dispatcher.onPosted(maps(title = ""), drawing(departArrow))

        assertEquals(
            PostedOutcome.UnreadableNavigation(ArrowRecognition.Known(ArrowMatch("maneuver_depart", 0)), firstForKey = true),
            outcome
        )
        assertEquals(emptyList<NavInstruction?>(), collected.instructions)
    }

    /** Once per key: Maps reposts the same key every few seconds while it guides. */
    @Test
    fun anUnreadableNavigationKeyIsFirstOnlyOnce() = runTest {
        dispatcher.onPosted(maps(title = ""), drawing(departArrow))

        val repeated = dispatcher.onPosted(maps(title = ""), drawing(departArrow)) as PostedOutcome.UnreadableNavigation
        val otherKey = dispatcher.onPosted(maps(title = "").copy(key = "$MAPS_KEY-2"), drawing(departArrow))
            as PostedOutcome.UnreadableNavigation

        assertEquals(false, repeated.firstForKey)
        assertEquals(true, otherKey.firstForKey)
    }

    /** Removing the notification ends it: the next trip under the same key is reported again. */
    @Test
    fun anUnreadableKeyIsFirstAgainAfterItsRemoval() = runTest {
        dispatcher.onPosted(maps(title = ""), drawing(departArrow))
        dispatcher.onRemoved(maps(title = ""))

        val again = dispatcher.onPosted(maps(title = ""), drawing(departArrow)) as PostedOutcome.UnreadableNavigation

        assertEquals(true, again.firstForKey)
    }

    @Test
    fun anIgnoredAppOnlyRetiresItsKeyFromTheCallBus() = runTest {
        val collected = collectAll()
        val firefox = ListenedNotification<String, String>(key = "0|org.mozilla.firefox|1|null|10180", packageName = "org.mozilla.firefox")

        val outcome = dispatcher.onPosted(firefox, drawing(null))

        assertEquals(PostedOutcome.Ignored, outcome)
        assertEquals(listOf(CallNotificationEvent.Removed(firefox.key)), collected.calls)
        assertEquals(emptyList<MessageNotificationEvent>(), collected.messages)
        assertEquals(emptyList<NavInstruction?>(), collected.instructions)
    }

    @Test
    fun removingAMessageRetiresItsKeyOnTheMessageAndCallBuses() = runTest {
        val collected = collectAll()

        val route = dispatcher.onRemoved(whatsAppMessage().copy(key = TELEGRAM_KEY, packageName = "org.telegram.messenger"))

        assertEquals(NotificationRoute.MESSAGE, route)
        assertEquals(listOf(CallNotificationEvent.Removed(TELEGRAM_KEY)), collected.calls)
        assertEquals(listOf<MessageNotificationEvent>(MessageNotificationEvent.Removed(TELEGRAM_KEY)), collected.messages)
    }

    @Test
    fun removingTheMapsNavigationNotificationClearsTheInstruction() = runTest {
        val collected = collectAll()

        val route = dispatcher.onRemoved(maps())

        assertEquals(NotificationRoute.NAVIGATION, route)
        assertEquals(listOf<NavInstruction?>(null), collected.instructions)
        assertEquals(listOf(CallNotificationEvent.Removed(MAPS_KEY)), collected.calls)
    }

    @Test
    fun removingACallRetiresOnlyTheCall() = runTest {
        val collected = collectAll()
        dispatcher.onPosted(dialer(callType = 2), drawing(null))

        val route = dispatcher.onRemoved(dialer(callType = 2))

        assertEquals(NotificationRoute.CALL, route)
        assertEquals(CallNotificationEvent.Removed(DIALER_KEY), collected.calls.last())
        assertEquals(emptyList<MessageNotificationEvent>(), collected.messages)
        assertEquals(emptyList<NavInstruction?>(), collected.instructions)
    }

    @Test
    fun losingTheListenerTellsAllThreeBuses() = runTest {
        val collected = collectAll()

        dispatcher.onListenerDisconnected()

        assertEquals(listOf<CallNotificationEvent>(CallNotificationEvent.ListenerDisconnected), collected.calls)
        assertEquals(listOf<MessageNotificationEvent>(MessageNotificationEvent.ListenerDisconnected), collected.messages)
        assertEquals(listOf<NavInstruction?>(null), collected.instructions)
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
        const val CALL_STYLE = "android.app.Notification\$CallStyle"
        const val DIALER_KEY = "0|com.google.android.dialer|1|null|10133"
        const val WHATSAPP_KEY = "0|com.whatsapp|1|abc@s.whatsapp.net|10150"
        const val TELEGRAM_KEY = "0|org.telegram.messenger|1|42|10160"
        const val MAPS_KEY = "0|com.google.android.apps.maps|1|null|10170"
    }
}
