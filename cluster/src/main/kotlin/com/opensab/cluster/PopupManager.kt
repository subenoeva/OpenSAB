package com.opensab.cluster

import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorPopUp
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.logging.Log
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.PopUpPriority
import com.opensab.sab.session.projection.QueuedPopUp
import com.opensab.sab.session.projection.ScreenProjector
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Arbitrates modal popups by priority on top of the existing [PopUpQueue].
 *
 * On a cluster with no modal layer a popup cannot be drawn at all, so it becomes the one thing
 * such a panel can show: the matching bit of the status bar. The substitution lives here and not
 * in the projection policy for two reasons — the bit has to be switched off when the popup would
 * have expired, and this class already owns that watchdog; and the status bar mask has a single
 * owner in [ActiveIndicatorsPublisher], so writing a bare mask from anywhere else would blank the
 * indicators the music and navigation adapters had lit.
 *
 * @param onRestoreBase re-projects the page covered by the modal once the queue drains.
 */
class PopupManager(
    private val screenProjector: ScreenProjector,
    private val onRestoreBase: suspend () -> Unit,
    private val capabilities: VehicleCapabilitiesHolder,
    private val activeIndicators: ActiveIndicatorsPublisher,
    private val projectionMirror: ProjectionMirror,
    private val queue: PopUpQueue = PopUpQueue()
) {

    var activePopUp: QueuedPopUp? = null
        private set

    private var scope: CoroutineScope? = null
    private var expiryJob: Job? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    suspend fun post(popUp: QueuedPopUp) {
        if (!capabilities.state.value.supportsModals) {
            degradeToIndicator(popUp)
            return
        }
        val active = activePopUp
        when {
            active == null -> present(popUp)

            popUp.priority.outranks(active.priority) -> {
                Log.d("PopUp %s preempts %s", popUp.id, active.id)
                queue.enqueue(active)
                present(popUp)
            }

            else -> queue.enqueue(popUp)
        }
    }

    /**
     * Deliberately silent towards the mirror: with no modal layer the rider has no modal in front
     * of them, so the app must not draw one either. The bit is all there is to show, and it travels
     * through [activeIndicators] like any other.
     */
    private suspend fun degradeToIndicator(popUp: QueuedPopUp) {
        val indicator = popUp.priority.toIndicator()
        Log.d("PopUp %s degraded to indicator %s", popUp.id, indicator)
        activeIndicators.set(indicator, enabled = true)

        expiryJob?.cancel()
        expiryJob = scope?.launch {
            delay(popUp.duration)
            activeIndicators.set(indicator, enabled = false)
        }
    }

    /**
     * The mirrored modal declares what [onInput] does, and that is all a modal does: dismiss.
     *
     * An incoming call does not travel through this layer — it is a page on the interrupt stack,
     * and the page owns the press. A modal with no real actions genuinely only dismisses, and the
     * buttons the cluster paints say so too, `POPUP_CANCEL` on both sides by default.
     */
    private fun QueuedPopUp.toMirror(): MirrorPopUp = MirrorPopUp(
        id = id,
        message = message,
        priority = priority,
        actions = listOf(
            MirrorAction(MirrorActionToken.DISMISS, FunctionId.BACK, MirrorSlot.LEFT),
            MirrorAction(MirrorActionToken.DISMISS, FunctionId.SELECT, MirrorSlot.RIGHT)
        )
    )

    private fun PopUpPriority.toIndicator(): ActiveIndicator = when (this) {
        PopUpPriority.INCOMING_CALL -> ActiveIndicator.PHONE
        PopUpPriority.NAVIGATION_ALERT -> ActiveIndicator.NAVIGATION
        PopUpPriority.SYSTEM_NOTIFICATION -> ActiveIndicator.MESSAGE
    }

    suspend fun onInput(functionId: FunctionId): Boolean {
        if (activePopUp == null) {
            return false
        }
        when (functionId) {
            FunctionId.SELECT, FunctionId.BACK -> dismissActive()
            else -> Log.d("Handlebar press %s swallowed by the active modal", functionId)
        }
        return true
    }

    suspend fun dismissActive() {
        val active = activePopUp ?: return
        Log.d("Dismissing popup %s", active.id)
        activePopUp = null
        stopExpiryWatchdog()
        projectionMirror.publishPopUp(null)

        screenProjector.dismissActivePopUp()
            .onFailure { Log.w(it, "Could not dismiss popup %s", active.id) }

        val next = queue.poll()
        if (next != null) {
            present(next)
        } else {
            onRestoreBase()
        }
    }

    fun clear() {
        activePopUp = null
        expiryJob?.cancel()
        expiryJob = null
        scope = null
        queue.clear()
    }

    /**
     * Drops the auto-dismiss watchdog, unless the coroutine asking is the watchdog itself.
     *
     * `Job.cancel()` on the job you are running in does not stop the current line: it marks the job
     * cancelled and the next suspension point throws. The watchdog dismisses by calling
     * [dismissActive], so cancelling it from there would take the dismiss frame and [onRestoreBase]
     * with it.
     *
     * **Owner: this class.** The watchdog has one owner and one canceller.
     */
    private suspend fun stopExpiryWatchdog() {
        val watchdog = expiryJob
        expiryJob = null
        if (watchdog != null && watchdog !== currentCoroutineContext()[Job]) {
            watchdog.cancel()
        }
    }

    private suspend fun present(popUp: QueuedPopUp) {
        activePopUp = popUp
        projectionMirror.publishPopUp(popUp.toMirror())
        screenProjector.postPopUp(popUp)
            .onFailure { Log.w(it, "Could not project popup %s", popUp.id) }

        expiryJob?.cancel()
        expiryJob = scope?.launch {
            delay(popUp.duration)
            Log.d("PopUp %s expired after %d ms", popUp.id, popUp.duration.inWholeMilliseconds)
            dismissActive()
        }
    }
}
