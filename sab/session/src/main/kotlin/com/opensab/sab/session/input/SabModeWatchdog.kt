package com.opensab.sab.session.input

import com.opensab.logging.Log
import com.opensab.sab.protocol.input.DashboardMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Reports that the cluster never handed the screen over after a REQUEST_SAB_MODE.
 *
 * Sole owner of the "did the BTU grant SAB_MODE?" question. It observes and reports; the mode
 * itself belongs to the FunctionNotification collector in [DefaultInputDispatcher] and is never
 * written here. A grant seen at any point of the wait settles it: a rider who goes back to HU_MODE
 * afterwards was granted the screen.
 *
 * Exists because the Receipt of the 0x0A only acknowledges the command: a session where the BTU
 * acknowledges every frame and the cluster still paints the native tachometer is otherwise
 * indistinguishable, in the logs, from a session that works.
 */
class SabModeWatchdog(
    /**
     * The rider's patience. Read at each [start]: a change applies to the next request, never to a
     * wait already running. This class is its only reader.
     */
    private val timeout: suspend () -> Duration
) {

    constructor(timeout: Duration) : this({ timeout })

    fun start(
        mode: StateFlow<DashboardMode>,
        scope: CoroutineScope,
        onNotGranted: () -> Unit
    ): Job = scope.launch {
        val wait = timeout()
        val granted = withTimeoutOrNull(wait) { mode.first { it == DashboardMode.SAB_MODE } }
        if (granted == null) {
            Log.w(
                "BTU has NOT granted SAB_MODE %d ms after REQUEST_SAB_MODE: no 0x09 notification, cluster still owns the screen",
                wait.inWholeMilliseconds
            )
            Log.i(
                "Hint, not a diagnosis: a d-pad press on the handlebar has made the cluster hand over before; " +
                    "it may be holding the screen for a warning of its own, which the protocol cannot read"
            )
            onNotGranted()
        }
    }
}
