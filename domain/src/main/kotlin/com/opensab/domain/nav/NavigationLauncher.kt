package com.opensab.domain.nav

interface NavigationLauncher {
    fun launchNavigation(query: String, preferredApp: NavigationApp = NavigationApp.GOOGLE_MAPS): NavigationLaunch
    fun isAppInstalled(app: NavigationApp): Boolean
}

/** Whether the route reached the navigation app, and whether the phone was locked when it did. */
sealed interface NavigationLaunch {
    data object NotStarted : NavigationLaunch
    data class Started(val deviceLocked: Boolean) : NavigationLaunch
}
