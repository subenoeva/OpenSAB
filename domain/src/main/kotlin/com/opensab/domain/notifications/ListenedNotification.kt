package com.opensab.domain.notifications

import com.opensab.domain.call.CallNotification
import com.opensab.domain.message.HistoricMessage
import com.opensab.domain.message.MessageNotification
import com.opensab.domain.nav.NavigationNotification

/** The `android.app.Person` a notification names, reduced to the two fields OpenSAB reads. */
data class NotificationPerson(val name: String?, val uri: String?)

/**
 * One notification action as the reply picker reads it: the handle to fire, the semantic the app
 * gave it, whether one of its `RemoteInput`s takes free-form text, and whether it has an intent.
 */
data class NotificationAction<out A : Any>(
    val handle: A,
    val semanticAction: Int,
    val acceptsFreeFormInput: Boolean,
    val hasIntent: Boolean
) {
    companion object {
        /** Mirror `NotificationCompat.Action.SEMANTIC_ACTION_*`, which the JVM cannot see. */
        const val SEMANTIC_ACTION_NONE = 0
        const val SEMANTIC_ACTION_REPLY = 1
        const val SEMANTIC_ACTION_MARK_AS_READ = 2
    }
}

/**
 * One posted notification, reduced to what OpenSAB reads from it. Built only by
 * `OpenSabNotificationListenerService` (`:app`), which translates the `StatusBarNotification` and
 * nothing else; every decision about it is made on this side of the seam, where the suite sees it.
 *
 * [texts] and [ints] hold only the extras named in the companion, and only those the notification
 * carried: a missing key is a missing extra, never an empty string or a 0.
 *
 * The handles are type parameters for the reason `PendingIntentCallCommands` gives: [I] is the
 * dialer's `PendingIntent` in production and [A] the reply `NotificationCompat.Action`; the JVM can
 * build neither.
 */
data class ListenedNotification<out I : Any, out A : Any>(
    val key: String,
    val packageName: String,
    val postTimeMillis: Long = 0L,
    val category: String? = null,
    val template: String? = null,
    val isGroupSummary: Boolean = false,
    val isOngoing: Boolean = false,
    val isForegroundService: Boolean = false,
    val texts: Map<String, String> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val callPerson: NotificationPerson? = null,
    val answerIntent: I? = null,
    val declineIntent: I? = null,
    val hangUpIntent: I? = null,
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean = false,
    val messages: List<HistoricMessage> = emptyList(),
    val actions: List<NotificationAction<A>> = emptyList()
) {

    val route: NotificationRoute
        get() = NotificationRouter.route(packageName = packageName, category = category, template = template)

    fun toCallNotification(): CallNotification = CallNotification(
        key = key,
        packageName = packageName,
        category = category,
        template = template,
        callType = ints[EXTRA_CALL_TYPE],
        title = texts[EXTRA_TITLE],
        personName = callPerson?.name,
        personUri = callPerson?.uri
    )

    fun toMessageNotification(): MessageNotification = MessageNotification(
        packageName = packageName,
        key = key,
        title = texts[EXTRA_TITLE],
        text = texts[EXTRA_TEXT],
        conversationTitle = conversationTitle,
        isGroupConversation = isGroupConversation,
        isGroupSummary = isGroupSummary,
        isOngoing = isOngoing,
        isForegroundService = isForegroundService,
        postTimeMillis = postTimeMillis,
        messages = messages
    )

    /**
     * The action a reply goes through, or `null`: one that takes free-form input and can be fired,
     * and of those the first marked `SEMANTIC_ACTION_REPLY`, else the first.
     */
    fun replyAction(): A? = actions
        .filter { it.acceptsFreeFormInput && it.hasIntent }
        .minByOrNull { if (it.semanticAction == NotificationAction.SEMANTIC_ACTION_REPLY) 0 else 1 }
        ?.handle

    /** [arrow] is the name of the app's drawing its large icon matched, or `null`. */
    fun toNavigationNotification(arrow: String?): NavigationNotification = NavigationNotification.fromExtras(
        packageName = packageName,
        arrow = arrow,
        text = texts::get,
        int = ints::get
    )

    companion object {
        const val EXTRA_TITLE = NavigationNotification.EXTRA_TITLE
        const val EXTRA_TEXT = NavigationNotification.EXTRA_TEXT
        const val EXTRA_SUB_TEXT = NavigationNotification.EXTRA_SUB_TEXT
        const val EXTRA_SHORT_CRITICAL_TEXT = NavigationNotification.EXTRA_SHORT_CRITICAL_TEXT
        const val EXTRA_PROGRESS = NavigationNotification.EXTRA_PROGRESS
        const val EXTRA_PROGRESS_MAX = NavigationNotification.EXTRA_PROGRESS_MAX
        const val EXTRA_CALL_TYPE = "android.callType"

        /** The text extras the listener copies into [texts]. */
        val TEXT_EXTRAS = listOf(EXTRA_TITLE, EXTRA_TEXT, EXTRA_SUB_TEXT, EXTRA_SHORT_CRITICAL_TEXT)

        /** The integer extras the listener copies into [ints]. */
        val INT_EXTRAS = listOf(EXTRA_CALL_TYPE, EXTRA_PROGRESS, EXTRA_PROGRESS_MAX)
    }
}
