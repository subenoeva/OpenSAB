package com.opensab.sab.session.projection

import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The lower the [rank], the more urgent the popup. Calls take 1 and navigation 100; notifications
 * have no rank of their own, so they sit below both.
 */
enum class PopUpPriority(val rank: Int) {
    INCOMING_CALL(1),
    NAVIGATION_ALERT(100),
    SYSTEM_NOTIFICATION(1000);

    fun outranks(other: PopUpPriority): Boolean = rank < other.rank
}

/**
 * [icon] is the content icon, a fixed byte of the POP_UP frame: the emitter always
 * declares it, and there is no fallback to guess one.
 * [leftActionIcon] and [rightActionIcon] are the two action buttons a POP_UP frame can carry. The
 * popup declares them, the projector does not invent them. A popup that declares neither emits no
 * button block at all.
 */
data class QueuedPopUp(
    val id: String,
    val message: String,
    val icon: IconId,
    val priority: PopUpPriority = PopUpPriority.SYSTEM_NOTIFICATION,
    val duration: Duration = 5.seconds,
    val leftActionIcon: IconId? = IconId.POPUP_CANCEL,
    val rightActionIcon: IconId? = IconId.POPUP_CANCEL,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(icon != IconId.IC_NONE) { "A popup declares its content icon: IC_NONE would paint a blank" }
    }
}

sealed interface RenderState {

    data object Idle : RenderState

    data class Projecting(
        val baseFrame: ProjectionFrame,
        val activePopUp: QueuedPopUp? = null
    ) : RenderState
}
