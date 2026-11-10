package com.opensab.cluster.mirror

import com.opensab.logging.Log
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.session.projection.ProjectionRejectedException
import com.opensab.sab.session.projection.ProjectionTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Sole owner of the mirrored state.
 *
 * Lives apart from `BaseStateManager` so that `DashboardViewModel` can inject it alone without
 * dragging the whole engine into the UI layer.
 *
 * One rule governs every method here: **only [publishPage] mounts a page.** The other publishers
 * describe a page already on
 * screen, so on any other state they are dropped instead of promoting the mirror into a
 * `Projecting` the cluster never reached.
 */
class ProjectionMirror {

    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    /**
     * The native tachometer owns the screen.
     *
     * Says **where the screen is**, not whether a request was answered: an outstanding
     * [markHandoverRequested] survives, because a re-delivery of the current mode is not a verdict.
     * Coming from anywhere else there is no request in flight to preserve — a transition from
     * `Projecting` means the cluster took the screen back — so the flag starts down.
     */
    fun publishClusterOwnsScreen() {
        _state.update { current ->
            current as? MirrorState.ClusterOwnsScreen ?: MirrorState.ClusterOwnsScreen(handoverPending = false)
        }
    }

    /**
     * The cluster did not grant the screen. This one **is** the verdict, so it always clears
     * [MirrorState.ClusterOwnsScreen.handoverPending] and hands the handover button back to the
     * rider.
     */
    fun publishHandoverDenied() {
        _state.value = MirrorState.ClusterOwnsScreen(handoverPending = false)
    }

    /**
     * The wait for the 0x09 ran out, as told by `SabModeWatchdog`: the button goes back to the
     * rider and [MirrorState.ClusterOwnsScreen.grantOverdue] goes up. Only meaningful while the
     * cluster owns the screen; anywhere else it is dropped, so a late verdict neither mounts a
     * tachometer on an idle mirror nor unmounts a page.
     */
    fun publishGrantOverdue() {
        _state.update { current ->
            if (current is MirrorState.ClusterOwnsScreen) {
                MirrorState.ClusterOwnsScreen(handoverPending = false, grantOverdue = true)
            } else {
                Log.d("Mirror overdue grant dropped: the cluster does not own the screen (state is %s)", current)
                current
            }
        }
    }

    /**
     * A REQUEST_SAB_MODE is out and its verdict has not come back, so the UI disables the handover
     * button. Only meaningful while the cluster owns the screen: there is nothing outstanding to
     * wait for when the mirror is idle or already projecting.
     */
    fun markHandoverRequested() {
        _state.update { current ->
            if (current is MirrorState.ClusterOwnsScreen) {
                MirrorState.ClusterOwnsScreen(handoverPending = true)
            } else {
                current
            }
        }
    }

    /**
     * Mounts [page] with its navigation [trail]. The delivery resets to
     * [MirrorDelivery.InFlight] — a fresh frame is unacknowledged until the cluster answers — while
     * the indicators and the modal carry over: neither is torn down by projecting a page, and
     * `PopupManager.onRestoreBase` re-projects with the modal already dismissed.
     */
    fun publishPage(page: MirrorPage, trail: List<String>) {
        _state.update { current ->
            val previous = current as? MirrorState.Projecting
            MirrorState.Projecting(
                page = page,
                popUp = previous?.popUp,
                trail = trail,
                indicators = previous?.indicators ?: emptySet(),
                delivery = MirrorDelivery.InFlight
            )
        }
    }

    /** Fate of the frame written for the page currently mounted. */
    fun publishDelivery(delivery: MirrorDelivery) = updateProjecting("delivery") {
        it.copy(delivery = delivery)
    }

    /** Raises the modal layer over the page, or drops it with `null`. */
    fun publishPopUp(popUp: MirrorPopUp?) = updateProjecting("popup") {
        it.copy(popUp = popUp)
    }

    /** Replaces the whole status bar set; the cluster carries the mask as one field, not per bit. */
    fun publishIndicators(indicators: Set<ActiveIndicator>) = updateProjecting("indicators") {
        it.copy(indicators = indicators)
    }

    /** No session, or the engine stopped. */
    fun clear() {
        _state.value = MirrorState.Idle
    }

    private inline fun updateProjecting(
        what: String,
        crossinline change: (MirrorState.Projecting) -> MirrorState.Projecting
    ) {
        _state.update { current ->
            if (current is MirrorState.Projecting) {
                change(current)
            } else {
                Log.d("Mirror %s dropped: no page is mounted (state is %s)", what, current)
                current
            }
        }
    }
}

/**
 * Translates what `ScreenProjector.updateBaseScreen` returned into what the rider is shown about it.
 *
 * No silent default: anything the write path can throw beyond the two projection failures is a
 * frame the cluster never acknowledged, which is exactly what [MirrorDelivery.TimedOut] means to
 * the rider — but it is logged rather than absorbed, because it is a case nobody enumerated.
 *
 * @param handoverConfirmed false when the frame went out on an assumed grant. A Receipt then proves
 *   the BTU took the bytes and nothing about the screen; a failure is a failure either way.
 */
internal fun mirrorDeliveryOf(result: Result<*>, handoverConfirmed: Boolean = true): MirrorDelivery {
    val error = result.exceptionOrNull()
        ?: return if (handoverConfirmed) MirrorDelivery.Confirmed else MirrorDelivery.HandoverUnconfirmed
    return when (error) {
        is ProjectionRejectedException -> MirrorDelivery.Rejected(error.status, error.errorPosition)
        is ProjectionTimeoutException -> MirrorDelivery.TimedOut
        else -> {
            Log.w(error, "Frame write failed in a way the mirror has no state for")
            MirrorDelivery.TimedOut
        }
    }
}
