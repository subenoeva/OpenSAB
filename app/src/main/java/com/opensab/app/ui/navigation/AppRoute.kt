package com.opensab.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Type-safe navigation routes for AndroidX Navigation 3. */
@Serializable
sealed interface AppRoute : NavKey {

    @Serializable
    data object Scan : AppRoute

    @Serializable
    data object Dashboard : AppRoute

    @Serializable
    data object Settings : AppRoute

    /** Order and visibility of the cluster's root carousel. */
    @Serializable
    data object RootMenuSettings : AppRoute

    /** The recorded sessions; a session's detail is state of the same screen, not a route. */
    @Serializable
    data object Sessions : AppRoute

    /**
     * A `data class` because [isIntro] rides in the route: captured from the same read that chose
     * this route, not observed. Read from DataStore inside the screen instead, the footer would
     * paint a back arrow for one frame on every first run before swapping it for "Continue".
     *
     * @param isIntro true only for the automatic first-run showing, pushed by `AppStartViewModel`.
     */
    @Serializable
    data class Permissions(val isIntro: Boolean = false) : AppRoute
}
