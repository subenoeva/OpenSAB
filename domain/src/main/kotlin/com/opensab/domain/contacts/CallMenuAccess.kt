package com.opensab.domain.contacts

/**
 * Which rows of the call menu are open, and the favourites themselves when theirs is.
 *
 * The list travels instead of a count on purpose: the menu has to read the address book anyway to
 * know whether its second row opens, and the dialler cannot read it at mount time — building a page
 * observes nothing. So the one read serves both, and the seed carries what it found.
 */
data class CallMenuGates(
    val voice: Boolean,
    val favorites: List<CallTarget>
) {
    companion object {
        /** What the menu shows until it has asked. Closed, because a closed row never lies. */
        val CLOSED = CallMenuGates(voice = false, favorites = emptyList())
    }
}

interface CallMenuAccess {
    suspend fun gates(): CallMenuGates
}

/**
 * The two permissions arrive as functions rather than as a `PermissionProbe`, so this package never
 * has to see `com.opensab.app.ui.permissions` — the graph binds them in `ContactsModule`, the way
 * `WeatherPageProvider` takes its clock as `nowMillis`.
 *
 * Nothing is read without permission, and that is not tidiness: `ContentResolver.query` on the
 * address book throws `SecurityException` without `READ_CONTACTS`.
 */
class DefaultCallMenuAccess(
    private val contactsGranted: () -> Boolean,
    private val phoneGranted: () -> Boolean,
    private val directory: ContactDirectory
) : CallMenuAccess {

    override suspend fun gates(): CallMenuGates {
        if (!contactsGranted() || !phoneGranted()) return CallMenuGates.CLOSED
        return CallMenuGates(voice = true, favorites = ContactMatcher.favorites(directory.starred()))
    }
}
