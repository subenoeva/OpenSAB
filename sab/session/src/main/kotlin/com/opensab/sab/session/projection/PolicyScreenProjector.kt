package com.opensab.sab.session.projection

import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.protocol.projection.Receipt
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder

/**
 * Applies [VehicleFramePolicy] to everything on its way to the cluster.
 *
 * A decorator rather than a branch inside [DefaultScreenProjector] because the projector already
 * owns receipt tracking, the one-command-in-flight mutex and the pending queue; degrading a frame
 * for the hardware is orthogonal to all three, and testing it should not need any of them.
 *
 * It deliberately does NOT turn a modal into an indicator bit. That substitution needs the modal's
 * lifetime — the bit has to go out when the popup would have expired — and it has to go through
 * [ActiveIndicatorsPublisher], the single owner of the status bar mask. Both live in
 * [com.opensab.cluster.PopupManager], which is where the substitution is done.
 */
class PolicyScreenProjector(
    private val delegate: ScreenProjector,
    private val policy: VehicleFramePolicy,
    private val holder: VehicleCapabilitiesHolder
) : ScreenProjector by delegate {

    override suspend fun updateBaseScreen(frame: ProjectionFrame): Result<Receipt> =
        delegate.updateBaseScreen(policy.adapt(frame, holder.state.value))

    override suspend fun postPopUp(popUp: QueuedPopUp): Result<Receipt> =
        delegate.postPopUp(
            popUp.copy(message = policy.sanitizeMessage(popUp.message, holder.state.value))
        )
}
