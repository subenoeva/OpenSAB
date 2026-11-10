package com.opensab.domain.message

import com.opensab.domain.contacts.CallTarget

/**
 * Which rows of the message menu are open, and the favorites themselves when theirs is.
 *
 * The list travels instead of a count on purpose: the menu has to read the address book anyway to
 * know whether its second row opens, and the composer cannot read it at mount time — building a page
 * observes nothing. So the one read serves both, and the seed carries what it found.
 */
data class MessageMenuGates(
    val voice: Boolean,
    val favorites: List<CallTarget>
) {
    companion object {
        /** What the menu shows until it has asked. Closed, because a closed row never lies. */
        val CLOSED = MessageMenuGates(voice = false, favorites = emptyList())
    }
}

interface MessageMenuAccess {
    suspend fun gates(): MessageMenuGates
}
