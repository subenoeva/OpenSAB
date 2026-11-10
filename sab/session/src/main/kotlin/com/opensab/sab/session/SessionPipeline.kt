package com.opensab.sab.session

import com.opensab.sab.session.ble.GattTransport

/**
 * Runs the SAB connection pipeline on a [SabSession], registering every resource it acquires with
 * [SabSession.onClose] so a failure mid-pipeline and an explicit close release exactly the same
 * things, the same way. Implemented by [DefaultSessionPipeline].
 */
interface SessionPipeline {

    /** MTU -> CTS -> security -> identity -> channels -> assignment -> projector -> SAB_MODE. */
    suspend fun open(
        session: SabSession,
        transport: GattTransport,
        report: (ConnectionStep) -> Unit
    ): SabSessionState.Connected

    /**
     * The rider-facing half of an open: the LINK_UP cue. Called by the owner once the session is
     * published as Connected, never from [open] itself, so the tone follows the fact.
     */
    fun announceOpened(session: SabSession)

    /** The rider-facing half of a close: the LINK_DOWN cue and the "Disconnecting OpenSAB session" line. */
    fun announceClosing(session: SabSession)
}
