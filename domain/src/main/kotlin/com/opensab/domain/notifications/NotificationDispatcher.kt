package com.opensab.domain.notifications

import com.opensab.domain.call.CallNotification
import com.opensab.domain.call.CallNotificationBus
import com.opensab.domain.call.CallNotificationEvent
import com.opensab.domain.call.PendingIntentCallCommands
import com.opensab.domain.message.MessageNotificationBus
import com.opensab.domain.message.MessageNotificationEvent
import com.opensab.domain.message.RemoteInputReplyCommands
import com.opensab.domain.nav.ArrowBits
import com.opensab.domain.nav.ArrowRecognition
import com.opensab.domain.nav.ArrowRecognizer
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.nav.NavigationNotificationBus
import com.opensab.domain.nav.NavigationReaders

/** What [NotificationDispatcher.onPosted] did, for the listener to log under its own tag. */
sealed interface PostedOutcome {

    data class Call(val notification: CallNotification) : PostedOutcome

    data class Navigation(val arrow: ArrowRecognition, val instruction: NavInstruction) : PostedOutcome

    /**
     * The app's reader could not read it: nothing was posted and the cluster keeps the last maneuver.
     * [firstForKey] is `true` only the first time for this key since it was posted or last removed.
     */
    data class UnreadableNavigation(val arrow: ArrowRecognition, val firstForKey: Boolean) : PostedOutcome

    data object Message : PostedOutcome

    data object Ignored : PostedOutcome
}

/**
 * Posts each listened notification to the call, message and navigation buses. The listener
 * translates and logs; every decision about where a notification goes is taken here.
 *
 * **Owner of the unknown-arrow run** ([arrows]) **and of [unreadableKeys]**, both held for this
 * dispatcher's life. One dispatcher serves one `OpenSabNotificationListenerService` and is fed from
 * its main thread only, so neither is synchronised.
 *
 * @param interceptNavigation the rider's "Intercept GPS Notifications", read on every notification.
 *   Off, a navigation notification is dropped and the maneuver on the cluster is retired.
 * @param sendIntent fires a call intent: `PendingIntent.send()` in production.
 * @param sendReply fills the free-form `RemoteInput`s of the action with the text and fires it; the
 *   first argument is the package that posted the notification, for the log.
 */
class NotificationDispatcher<I : Any, A : Any>(
    private val callBus: CallNotificationBus,
    private val messageBus: MessageNotificationBus,
    private val navigationBus: NavigationNotificationBus,
    private val arrows: ArrowRecognizer,
    private val interceptNavigation: () -> Boolean,
    private val sendIntent: (I) -> Unit,
    private val sendReply: suspend (packageName: String, action: A, text: String) -> Boolean
) {

    /** Navigation keys already reported unreadable; a key leaves it when its notification is removed. */
    private val unreadableKeys = mutableSetOf<String>()

    /** [drawArrow] is called only for a navigation notification: drawing the large icon costs. */
    fun onPosted(notification: ListenedNotification<I, A>, drawArrow: () -> ArrowBits?): PostedOutcome {
        val route = notification.route
        if (route != NotificationRoute.CALL) {
            callBus.post(CallNotificationEvent.Removed(notification.key))
        }

        return when (route) {
            NotificationRoute.CALL -> {
                val call = notification.toCallNotification()
                callBus.post(
                    CallNotificationEvent.Posted(
                        notification = call,
                        commands = PendingIntentCallCommands(
                            answerIntent = notification.answerIntent,
                            declineIntent = notification.declineIntent,
                            hangUpIntent = notification.hangUpIntent,
                            fallback = null,
                            send = sendIntent
                        )
                    )
                )
                PostedOutcome.Call(call)
            }

            NotificationRoute.NAVIGATION -> {
                if (!interceptNavigation()) {
                    navigationBus.post(null)
                    return PostedOutcome.Ignored
                }
                val reader = NavigationReaders.readerFor(notification.packageName) ?: return PostedOutcome.Ignored
                val arrow = arrows.recognize(drawArrow())
                val name = (arrow as? ArrowRecognition.Known)?.match?.name
                val instruction = reader.read(notification.toNavigationNotification(name))
                    ?: return PostedOutcome.UnreadableNavigation(arrow, unreadableKeys.add(notification.key))
                navigationBus.post(instruction)
                PostedOutcome.Navigation(arrow, instruction)
            }

            NotificationRoute.MESSAGE -> {
                messageBus.post(
                    MessageNotificationEvent.Posted(
                        notification = notification.toMessageNotification(),
                        reply = RemoteInputReplyCommands(notification.replyAction()) { action, text ->
                            sendReply(notification.packageName, action, text)
                        }
                    )
                )
                PostedOutcome.Message
            }

            NotificationRoute.IGNORE -> PostedOutcome.Ignored
        }
    }

    /** Returns the route the removed notification had, for the listener's log. */
    fun onRemoved(notification: ListenedNotification<I, A>): NotificationRoute {
        val route = notification.route
        callBus.post(CallNotificationEvent.Removed(notification.key))
        unreadableKeys.remove(notification.key)
        when (route) {
            NotificationRoute.NAVIGATION -> navigationBus.post(null)
            NotificationRoute.MESSAGE -> messageBus.post(MessageNotificationEvent.Removed(notification.key))
            NotificationRoute.CALL, NotificationRoute.IGNORE -> Unit
        }
        return route
    }

    fun onListenerDisconnected() {
        unreadableKeys.clear()
        navigationBus.post(null)
        messageBus.post(MessageNotificationEvent.ListenerDisconnected)
        callBus.post(CallNotificationEvent.ListenerDisconnected)
    }
}
