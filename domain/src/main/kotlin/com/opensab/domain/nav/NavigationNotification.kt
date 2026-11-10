package com.opensab.domain.nav

/**
 * One navigation notification as its app posted it, each extra in its own field, plus the name of
 * the app's arrow drawing that matched its large icon. Nothing is merged before a
 * [NavigationReader] sees it: mixing `text` and `subText` made every Maps maneuver read as an
 * arrival. Built only by [com.opensab.domain.notifications.ListenedNotification.toNavigationNotification].
 */
data class NavigationNotification(
    val packageName: String,
    val arrow: String?,
    val title: String?,
    val text: String?,
    val subText: String?,
    val progress: Int?,
    val progressMax: Int?,
    val shortCriticalText: String?
) {
    companion object {
        const val EXTRA_TITLE = "android.title"
        const val EXTRA_TEXT = "android.text"
        const val EXTRA_SUB_TEXT = "android.subText"
        const val EXTRA_PROGRESS = "android.progress"
        const val EXTRA_PROGRESS_MAX = "android.progressMax"
        const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

        /** [text] and [int] read one extra by key: `ListenedNotification` passes its extras maps. */
        fun fromExtras(
            packageName: String,
            arrow: String?,
            text: (String) -> CharSequence?,
            int: (String) -> Int?
        ): NavigationNotification = NavigationNotification(
            packageName = packageName,
            arrow = arrow,
            title = text(EXTRA_TITLE)?.toString(),
            text = text(EXTRA_TEXT)?.toString(),
            subText = text(EXTRA_SUB_TEXT)?.toString(),
            progress = int(EXTRA_PROGRESS),
            progressMax = int(EXTRA_PROGRESS_MAX),
            shortCriticalText = text(EXTRA_SHORT_CRITICAL_TEXT)?.toString()
        )
    }
}
