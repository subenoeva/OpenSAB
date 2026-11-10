package com.opensab.domain.notifications

import com.opensab.domain.nav.NavigationReaders

/** Where a posted notification belongs. Pure: the service does not decide, it asks. */
enum class NotificationRoute { CALL, NAVIGATION, MESSAGE, IGNORE }

/**
 * The one branch the notification listener is not allowed to hold itself.
 *
 * `OpenSabNotificationListenerService` (`:app`) extends a platform service, so nothing in it is
 * covered by the suite. Pulling the decision out here keeps the service down to translation and
 * lets the tests pin the ordering that matters: a notification is navigation only if it is
 * `category=navigation` and `NavigationReaders` has a reader for its package.
 */
object NotificationRouter {

    /**
     * CALL wins over MESSAGE: a WhatsApp call is a call, not a message from WhatsApp.
     *
     * The order of these branches is the whole class. `MessagingPackages.isMessaging` accepts
     * `com.whatsapp` whatever the notification carries, so asking it first would send a call down
     * the messaging path and the rider would get a message from WhatsApp while the phone rang.
     */
    fun route(packageName: String, category: String?, template: String?): NotificationRoute = when {
        template == CALL_STYLE_TEMPLATE && category == CATEGORY_CALL -> NotificationRoute.CALL
        category == CATEGORY_NAVIGATION && NavigationReaders.readerFor(packageName) != null ->
            NotificationRoute.NAVIGATION
        MessagingPackages.isMessaging(packageName) -> NotificationRoute.MESSAGE
        else -> NotificationRoute.IGNORE
    }

    private const val CALL_STYLE_TEMPLATE = "android.app.Notification\$CallStyle"
    private const val CATEGORY_CALL = "call"
    private const val CATEGORY_NAVIGATION = "navigation"
}
