package com.opensab.domain.nav

/** The apps OpenSAB can send a destination to: only those it also has a [NavigationReader] for. */
enum class NavigationApp(val packageName: String) {
    GOOGLE_MAPS("com.google.android.apps.maps")
}
