package com.opensab.domain.nav

/**
 * Reads the guidance notification of one navigation app. One implementation per app, each with
 * vectors from a real dump of that app's notification; [NavigationReaders] owns the list.
 */
interface NavigationReader {
    val packageName: String

    /** `null` when the notification is not guidance (an empty title, say). */
    fun read(notification: NavigationNotification): NavInstruction?
}
