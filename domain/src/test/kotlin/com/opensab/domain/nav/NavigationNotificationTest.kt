package com.opensab.domain.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationNotificationTest {

    /** Extras of the Maps guidance notification in `dumpsys-notif-1.txt` (Maps 26.38, Android 17). */
    private val mapsExtras: Map<String, Any?> = mapOf(
        "android.title" to "Dirígete hacia Rúa do Castro",
        "android.text" to null,
        "android.subText" to "Llegada: 13:10",
        "android.progress" to 12,
        "android.progressMax" to 2237,
        "android.shortCriticalText" to ""
    )

    @Test
    fun mapsExtrasKeepTextAndSubTextApart() {
        val notification = NavigationNotification.fromExtras(
            packageName = "com.google.android.apps.maps",
            arrow = "maneuver_depart",
            text = { mapsExtras[it] as CharSequence? },
            int = { mapsExtras[it] as Int? }
        )

        assertEquals(
            NavigationNotification(
                packageName = "com.google.android.apps.maps",
                arrow = "maneuver_depart",
                title = "Dirígete hacia Rúa do Castro",
                text = null,
                subText = "Llegada: 13:10",
                progress = 12,
                progressMax = 2237,
                shortCriticalText = ""
            ),
            notification
        )
    }

    @Test
    fun absentExtrasStayNull() {
        val notification = NavigationNotification.fromExtras("com.google.android.apps.maps", null, { null }, { null })

        assertNull(notification.arrow)
        assertNull(notification.title)
        assertNull(notification.subText)
        assertNull(notification.progress)
    }
}
