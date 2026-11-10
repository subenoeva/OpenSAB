package com.opensab.domain.call

/**
 * Raw values read from `Notification.extras`, with no judgement applied: deciding whether this is a
 * call worth showing, and in what state, is [CallNotificationParser]'s job on the JVM side.
 */
data class CallNotification(
    val key: String,
    val packageName: String,
    val category: String?,
    val template: String?,
    val callType: Int?,
    val title: String?,
    val personName: String?,
    val personUri: String?
)

/**
 * What can be done to the live call.
 *
 * It travels inside [CallNotificationEvent.Posted] instead of living in a global because the
 * platform handles — the notification's `PendingIntent`s — only exist at the edge Android built.
 * The source keeps the commands next to the state they act on, so "which call is live" and "how to
 * act on it" have the same owner.
 *
 * [answer] is `SELECT` while ringing, [reject] is `@Back` while ringing, [disconnect] is `@Back`
 * once the call is up.
 */
interface CallCommands {
    fun answer()
    fun reject()
    fun disconnect()
}

sealed interface CallNotificationEvent {

    data class Posted(
        val notification: CallNotification,
        val commands: CallCommands
    ) : CallNotificationEvent

    /**
     * The notification under [key] is no longer a live call, **however it stopped being one**.
     *
     * Retired from the shade is the obvious way and it is not the only one: Google Dialer answers a
     * rejection by **re-posting the very same key without its `CallStyle`** — the call moves to
     * screening, the notification stays, and nothing is ever removed. Read as "removed", that event
     * never came and the cluster sat on a ringing call for six and a half minutes.
     *
     * Carries the key because only that call is over. Both the bus and
     * [DefaultCallManager.onRemoved] filter on it, so an event about any other notification on the
     * phone moves nothing.
     */
    data class Removed(val key: String) : CallNotificationEvent

    data object ListenerDisconnected : CallNotificationEvent
}
