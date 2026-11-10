package com.opensab.domain.nav

/**
 * The navigation apps OpenSAB can read. **Owner of the reader list:** `NotificationRouter` only asks
 * it. Adding an app is adding its reader here, with vectors from a dump of its own notification;
 * nothing else changes.
 */
object NavigationReaders {

    val all: List<NavigationReader> = listOf(GoogleMapsReader)

    fun readerFor(packageName: String): NavigationReader? = all.firstOrNull { it.packageName == packageName }
}
