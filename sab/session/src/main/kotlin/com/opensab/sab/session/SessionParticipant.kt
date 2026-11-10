package com.opensab.sab.session

/**
 * A resource that lives with one [SabSession]: started once the pipeline connects, in a fixed
 * order, and closed in the reverse order when the session does. The order and the registration are
 * the caller's job — [onSessionStarted] runs, then the caller registers [onSessionClosing] with
 * [SabSession.onClose] right away, the same "register at acquisition" discipline
 * [SabSession.onClose] itself documents.
 */
interface SessionParticipant {
    val name: String

    /** On the session's confined dispatcher, after the pipeline connects. Launch into [SabSession.scope] only. */
    fun onSessionStarted(session: SabSession)

    /** On the session's confined dispatcher, before its scope is cancelled, in reverse start order. Must not launch. */
    fun onSessionClosing(session: SabSession)
}
