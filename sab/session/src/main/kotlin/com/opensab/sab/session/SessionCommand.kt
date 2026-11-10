package com.opensab.sab.session

/** An order [SabSessionController] serializes, one at a time, from its own logical thread. */
sealed interface SessionCommand {
    data class Start(val macAddress: String, val origin: SessionOrigin, val startId: Int) : SessionCommand
    data class Disconnect(val origin: SessionOrigin, val startId: Int) : SessionCommand
}
