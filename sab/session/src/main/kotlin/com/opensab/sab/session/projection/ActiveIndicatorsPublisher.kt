package com.opensab.sab.session.projection

import com.opensab.logging.Log
import com.opensab.sab.protocol.projection.ActiveIndicator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single owner of the cluster status bar — of its value **and** of its write order.
 *
 * `ACTIVE_INDICATORS_CONTROL` carries the whole bitmask, so an adapter that wrote only its own bit
 * would clear everybody else's; one set guarded by a lock computes the whole mask on each toggle.
 *
 * An unchanged mask is not re-sent: the adapters publish on every state
 * change of their source — a new track title, a new distance to the next turn — and those spend a
 * write on the shared ControlPoint for a frame the cluster already has.
 *
 * That last decision is what makes ordering load-bearing. Computing the mask under a lock and
 * writing outside it left two adapters free to compute 0x04 then 0x05 and enqueue their writes the
 * other way round — music starting while a call comes in, the ordinary case — and with no re-send
 * of an unchanged mask, nothing ever repaired it: the cluster kept the stale bar for the rest of
 * the session while this class and the in-app mirror agreed on the other one. Owning the value is
 * not owning the order.
 */
class ActiveIndicatorsPublisher(
    private val screenProjector: ScreenProjector
) {

    private val active = mutableSetOf<ActiveIndicator>()
    private var lastMask: Int = 0

    /**
     * Holds computing and writing together as one operation.
     *
     * A `Mutex` rather than the `synchronized` block below because [set] suspends across the write,
     * and a monitor cannot be held across a suspension point.
     */
    private val writeOrder = Mutex()

    private val _state = MutableStateFlow<Set<ActiveIndicator>>(emptySet())

    /**
     * The enabled set, for the in-app mirror to paint the status bar reactively. Read-only: the bar
     * is a projection of what the adapters have lit, never a place to light one.
     *
     * It moves in lockstep with [mask] — an unchanged mask emits nothing here either, for the same
     * reason it writes nothing to the cluster.
     */
    val state: StateFlow<Set<ActiveIndicator>> = _state.asStateFlow()

    /** Bitmask currently projected on the cluster. */
    val mask: Int
        get() = synchronized(active) { lastMask }

    /** Turns [indicator] on or off and projects the resulting mask when it actually changed. */
    suspend fun set(indicator: ActiveIndicator, enabled: Boolean) {
        writeOrder.withLock {
            val mask = synchronized(active) {
                if (enabled) active += indicator else active -= indicator
                val next = ActiveIndicator.maskOf(active)
                if (next == lastMask) {
                    null
                } else {
                    lastMask = next
                    _state.value = active.toSet()
                    next
                }
            } ?: return@withLock

            Log.d("Active indicators changed to 0x%02X (%s)", mask, active)
            screenProjector.updateActiveIndicators(mask)
                .onFailure { Log.w(it, "Could not project active indicators 0x%02X", mask) }
        }
    }

    /** Drops every indicator without writing to the cluster; used when the session ends. */
    fun clear() {
        synchronized(active) {
            active.clear()
            lastMask = 0
            _state.value = emptySet()
        }
    }

    /**
     * The session's own reset: registered first in [com.opensab.sab.session.SabSession.onClose] so it
     * runs last, after every indicator translator had its chance to react to its source stopping
     * (or, cancelled with the scope, did not). Without it the next session inherits whatever bits
     * survived — the publisher itself is the only thing guaranteed to still be there
     * to zero it.
     */
    fun forget() = clear()
}
