package com.opensab.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The listener extends `NotificationListenerService`, so no JVM test ever sees it. That is exactly
 * why the decision is out here: what is left in the service is translation with no branch of its
 * own.
 */
class NotificationRouterTest {

    @Test
    fun aCallStyleNotificationRoutesToCall() {
        assertEquals(
            NotificationRoute.CALL,
            NotificationRouter.route(
                packageName = "com.google.android.dialer",
                category = "call",
                template = CALL_STYLE
            )
        )
    }

    /**
     * The one that matters. WhatsApp publishes messages **and** calls, and
     * `MessagingPackages.isMessaging` accepts the package either way — so without CALL ahead of
     * MESSAGE a WhatsApp call would arrive as a message from WhatsApp.
     */
    @Test
    fun aWhatsAppCallRoutesToCallAndNotToMessage() {
        assertEquals(
            NotificationRoute.CALL,
            NotificationRouter.route(
                packageName = "com.whatsapp",
                category = "call",
                template = CALL_STYLE
            )
        )
    }

    @Test
    fun aWhatsAppMessageStillRoutesToMessage() {
        assertEquals(
            NotificationRoute.MESSAGE,
            NotificationRouter.route(
                packageName = "com.whatsapp",
                category = "msg",
                template = "android.app.Notification\$MessagingStyle"
            )
        )
    }

    @Test
    fun aMapsNotificationRoutesToNavigation() {
        assertEquals(
            NotificationRoute.NAVIGATION,
            NotificationRouter.route(
                packageName = "com.google.android.apps.maps",
                category = "navigation",
                template = null
            )
        )
    }

    /**
     * Maps also posts notifications that are not guidance (a saved parking spot, a commute alert).
     * Read as guidance they would take over the navigation page, and their removal would clear it.
     */
    @Test
    fun aMapsNotificationThatIsNotGuidanceIsIgnored() {
        listOf(null, "reminder", "recommendation").forEach { category ->
            assertEquals(
                "$category",
                NotificationRoute.IGNORE,
                NotificationRouter.route(
                    packageName = "com.google.android.apps.maps",
                    category = category,
                    template = null
                )
            )
        }
    }

    /** N4: a navigation app OpenSAB cannot read is ignored, not half-read. */
    @Test
    fun wazeIsIgnored() {
        assertEquals(
            NotificationRoute.IGNORE,
            NotificationRouter.route(packageName = "com.waze", category = "navigation", template = null)
        )
    }

    @Test
    fun calimotoIsIgnored() {
        assertEquals(
            NotificationRoute.IGNORE,
            NotificationRouter.route(packageName = "com.calimoto.calimoto", category = "navigation", template = null)
        )
    }

    @Test
    fun anythingElseIsIgnored() {
        assertEquals(
            NotificationRoute.IGNORE,
            NotificationRouter.route(
                packageName = "com.android.vending",
                category = "progress",
                template = null
            )
        )
    }

    private companion object {
        const val CALL_STYLE = "android.app.Notification\$CallStyle"
    }
}
