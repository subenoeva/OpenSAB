package com.opensab.domain.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface MessageSource {

    /**
     * The message the rider has not dealt with yet. It survives `BACK` and `MENU`: what those give
     * back is the screen, not the message. It goes null when the notification is retired on the phone.
     */
    val currentMessage: StateFlow<MessageInfo?>

    /** True while the message must hold the screen. Read by `MessageInterruptSource`. */
    val claimingScreen: StateFlow<Boolean>

    /**
     * True while the live notification published a `RemoteInput` to answer through. A property of the **notification** and not of the package: thirteen apps are in the table and
     * only some publish a reply action, and the same app does not publish one on a grouped summary
     * or a muted thread. So this is read at the instant of the press and it governs three things at
     * once — the right button of the frame, the `PRIMARY` zone of the mirror, and what `SELECT`
     * does — exactly as `canEnter` governs the weather entry of the carousel.
     */
    val canReply: StateFlow<Boolean>

    /**
     * `false` is every way it can fail to happen, and the caller does one thing with all of them:
     * it says so. Nothing here throws (see [MessageReplyCommands]).
     */
    suspend fun reply(text: String): Boolean

    /**
     * `@Back` / `@Menu`: give the screen back and keep the message unread. The bit stays lit and the
     * carousel entry stays open, which is the whole point of having a bit.
     */
    fun yieldScreen()

    /** Starts on the session scope. */
    fun start(scope: CoroutineScope)

    fun stop()
}
