package com.opensab.sab.session.input

import com.opensab.logging.Log
import com.opensab.sab.protocol.input.DashboardMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Two fallbacks for a cluster that is slow to hand over, both off unless the rider turns
 * them on. Read once per session, at open.
 */
data class SabModeFallbacks(
    /** Every HU_MODE notification before the first grant writes REQUEST_SAB_MODE again. */
    val rerequestOnHuMode: Boolean = false,
    /** After the timeout the engine projects as if the grant had arrived. */
    val projectWithoutGrant: Boolean = false
)

/**
 * Asks for SAB_MODE until the cluster grants it or the opening window closes.
 *
 * Sole owner of every REQUEST_SAB_MODE written before the first grant of a session: the opening
 * one, the one at [timeout], and, with [SabModeFallbacks.rerequestOnHuMode], one per HU_MODE
 * notification in between. After the first grant, or once the window has closed, it writes nothing:
 * returning to HU_MODE is the rider's call and is never re-requested. The handover button is the
 * rider asking, and belongs to `ClusterHandoverCoordinator`.
 *
 * The window is a 5 s `timeout` on the mode stream that writes
 * REQUEST_SAB_MODE again and then stops listening. [spacing] bounds the re-requests: without a
 * rate limit, a BTU that answered each request with a HU_MODE would be asked once per round
 * trip. The first HU_MODE is answered at once, the next ones no closer than [spacing].
 */
class SabModeRetry(
    private val timeout: Duration = 5.seconds,
    private val spacing: Duration = 1.seconds
) {

    private enum class Outcome { GRANTED, TIMED_OUT, HU_MODE, SPACED }

    /**
     * Writes the opening request before returning, and leaves the rest of the window running on
     * [scope].
     *
     * @param mode the cluster's mode as state; a SAB_MODE in it closes the window.
     * @param modeNotifications every mode notification as it arrives, repeats included: [mode]
     *   conflates a HU_MODE delivered on top of HU_MODE, which is exactly the one that matters here.
     * @param assumeGranted called once, after the request at the timeout, and only with
     *   [SabModeFallbacks.projectWithoutGrant].
     */
    suspend fun requestUntilGranted(
        mode: StateFlow<DashboardMode>,
        modeNotifications: Flow<DashboardMode>,
        scope: CoroutineScope,
        fallbacks: SabModeFallbacks,
        request: suspend () -> Unit,
        assumeGranted: () -> Unit
    ): Job {
        val huModeSeen = Channel<Unit>(Channel.CONFLATED)
        val granted = AtomicBoolean(false)
        // Subscribed before the opening request is written: the BTU's HU_MODE lands while that
        // request is still waiting for its Receipt.
        val listener = if (fallbacks.rerequestOnHuMode) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                modeNotifications.collect { notified ->
                    when (notified) {
                        DashboardMode.SAB_MODE -> granted.set(true)
                        DashboardMode.HU_MODE -> if (!granted.get()) huModeSeen.trySend(Unit)
                    }
                }
            }
        } else {
            null
        }

        request()

        return scope.launch {
            try {
                val outcome = awaitGrantOrTimeout(mode, huModeSeen, granted, request)
                if (outcome == Outcome.TIMED_OUT) {
                    Log.w("No 0x09 %d ms after REQUEST_SAB_MODE: requesting SAB_MODE once more", timeout.inWholeMilliseconds)
                    request()
                    if (fallbacks.projectWithoutGrant) {
                        assumeGranted()
                    }
                }
            } finally {
                listener?.cancel()
            }
        }
    }

    /**
     * One coroutine decides everything, so a request is never cancelled half-written and never
     * overlaps another. `select` is biased towards its first clause: a grant beats the timeout, and
     * the timeout beats a pending HU_MODE.
     */
    private suspend fun awaitGrantOrTimeout(
        mode: StateFlow<DashboardMode>,
        huModeSeen: Channel<Unit>,
        granted: AtomicBoolean,
        request: suspend () -> Unit
    ): Outcome = coroutineScope {
        val grant = launch { mode.first { it == DashboardMode.SAB_MODE } }
        val deadline = launch { delay(timeout) }
        var spaced: Job? = null

        var outcome: Outcome
        while (true) {
            val waitingForSpacing = spaced?.takeIf { it.isActive }
            outcome = select {
                grant.onJoin { Outcome.GRANTED }
                deadline.onJoin { Outcome.TIMED_OUT }
                if (waitingForSpacing != null) {
                    waitingForSpacing.onJoin { Outcome.SPACED }
                } else {
                    huModeSeen.onReceive { Outcome.HU_MODE }
                }
            }
            when (outcome) {
                Outcome.GRANTED, Outcome.TIMED_OUT -> break
                Outcome.SPACED -> continue
                Outcome.HU_MODE -> {
                    if (granted.get()) continue
                    Log.i("HU_MODE notified before the first grant: requesting SAB_MODE again")
                    spaced = launch { delay(spacing) }
                    request()
                }
            }
        }
        grant.cancel()
        deadline.cancel()
        spaced?.cancel()
        // [mode] is state: a grant the rider undid at once can be gone before `first` looks.
        if (granted.get()) Outcome.GRANTED else outcome
    }
}
