package com.opensab.domain.message

import com.opensab.domain.contacts.ContactDirectory
import com.opensab.domain.contacts.ContactMatcher

/**
 * The three permissions arrive as functions rather than as a `PermissionProbe`, so this package never
 * has to see `com.opensab.app.ui.permissions` — the graph binds them in Hilt modules, the way
 * `DefaultCallMenuAccess` does.
 *
 * Nothing is read without permission, preventing `SecurityException` when `READ_CONTACTS` is missing.
 */
class DefaultMessageMenuAccess(
    private val contactsGranted: () -> Boolean,
    private val micGranted: () -> Boolean,
    private val smsGranted: () -> Boolean,
    private val directory: ContactDirectory
) : MessageMenuAccess {

    override suspend fun gates(): MessageMenuGates {
        if (!contactsGranted() || !smsGranted()) return MessageMenuGates.CLOSED
        val voiceOpen = micGranted()
        val favorites = ContactMatcher.favorites(directory.starred())
        return MessageMenuGates(voice = voiceOpen, favorites = favorites)
    }
}
