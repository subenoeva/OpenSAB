package com.opensab.domain.message

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface MessageNotificationEvent {

    /**
     * @param reply how to answer this very notification, or `null` when it published no
     *   `RemoteInput` — a grouped summary, a muted thread, an app that offers none. The action
     *   rides on the event and not inside [MessageInfo] because that class is compared by
     *   structural equality by three live mechanisms (see [MessageReplyCommands]).
     */
    data class Posted(
        val notification: MessageNotification,
        val reply: MessageReplyCommands? = null
    ) : MessageNotificationEvent

    /**
     * The rider dealt with a thread on the phone.
     *
     * It carries the key and not a bare null the way [com.opensab.domain.nav.NavigationNotificationBus]
     * does: there is more than one thread alive at a time, and retiring the wrong one is worse than
     * retiring none.
     */
    data class Removed(val key: String) : MessageNotificationEvent

    data object ListenerDisconnected : MessageNotificationEvent
}

/**
 * The pipe from `OpenSabNotificationListenerService` (`:app`) to [DefaultMessageSource]. Built only
 * by `NotificationModule.provideMessageNotificationBus`. No replay: a message is an event, not a state — see [com.opensab.domain.call.CallNotificationBus]
 * for what makes its replay the exception and not the rule.
 *
 * It carries the untouched [MessageNotification] and not a parsed [MessageInfo]: the filter and the
 * discard policy belong on the JVM side of the seam, where the suite covers them.
 */
class MessageNotificationBus {

    private val _events = MutableSharedFlow<MessageNotificationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<MessageNotificationEvent> = _events.asSharedFlow()

    fun post(event: MessageNotificationEvent) {
        _events.tryEmit(event)
    }
}
