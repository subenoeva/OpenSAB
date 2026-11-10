package com.opensab.cluster.mirror

import com.opensab.sab.protocol.projection.ActiveIndicator

/** Fate of the last frame written to the cluster. */
sealed interface MirrorDelivery {
    data object InFlight : MirrorDelivery
    data object Confirmed : MirrorDelivery

    /**
     * The BTU acknowledged the frame, but it was written on an assumed grant: the cluster never
     * said it handed the screen over and may still be showing its own.
     */
    data object HandoverUnconfirmed : MirrorDelivery
    data class Rejected(val status: Int, val errorPosition: Int?) : MirrorDelivery
    data object TimedOut : MirrorDelivery
}

sealed interface MirrorState {

    /** No session, or the engine is stopped. */
    data object Idle : MirrorState

    /**
     * The cluster is in HU_MODE: the native tachometer owns the screen and no frame is being
     * emitted. [handoverPending] is true between a REQUEST_SAB_MODE and its verdict.
     *
     * [grantOverdue] is true once a wait for the 0x09 has run out. It says only that: the protocol
     * has no way to read why the cluster kept its screen, so what the UI hangs on it is a
     * suggestion and never a diagnosis.
     */
    data class ClusterOwnsScreen(
        val handoverPending: Boolean = false,
        val grantOverdue: Boolean = false
    ) : MirrorState

    data class Projecting(
        val page: MirrorPage,
        val popUp: MirrorPopUp? = null,
        val trail: List<String> = emptyList(),
        val indicators: Set<ActiveIndicator> = emptySet(),
        val delivery: MirrorDelivery = MirrorDelivery.InFlight
    ) : MirrorState
}
