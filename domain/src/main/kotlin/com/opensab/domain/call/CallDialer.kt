package com.opensab.domain.call

/**
 * Placing a call, which is the one thing telephony needs the platform for.
 *
 * Answering, rejecting and hanging up go through the `PendingIntent`s the `CallStyle` notification
 * publishes (`PendingIntentCallCommands`), which need no phone permission. Dialling out has no such
 * handle: it is `TelecomManager.placeCall`, and that is `CALL_PHONE`. Implemented by
 * `AndroidCallDialer` (`:app`).
 */
interface CallDialer {

    /**
     * `true` means **the intent is with the system**, not that the call connected: `placeCall`
     * returns `void` and the only thing observable here is that it did not throw. What happens next
     * arrives, if it arrives, through the notification the dialler posts, and the call parser has
     * only been checked against incoming-call notifications: an outgoing call may never show up.
     */
    suspend fun place(number: String): Boolean
}
