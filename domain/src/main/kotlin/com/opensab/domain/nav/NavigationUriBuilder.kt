package com.opensab.domain.nav

import java.net.URLEncoder

/**
 * Native navigation URIs only: a browser posts no turn-by-turn notification, so a web fallback
 * would leave the cluster blank.
 */
object NavigationUriBuilder {

    fun buildNavigationUri(query: String, app: NavigationApp = NavigationApp.GOOGLE_MAPS): String {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return when (app) {
            NavigationApp.GOOGLE_MAPS -> "google.navigation:q=$encoded&mode=d"
        }
    }
}
