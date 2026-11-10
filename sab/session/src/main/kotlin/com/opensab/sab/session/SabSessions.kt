package com.opensab.sab.session
 
import kotlinx.coroutines.flow.StateFlow

/** Read-only view of the one live session's state — the state [SabSessionController] owns. */
interface SabSessions {
    val state: StateFlow<SabSessionState>
}
