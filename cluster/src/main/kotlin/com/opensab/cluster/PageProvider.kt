package com.opensab.cluster

import com.opensab.cluster.mirror.MirrorPage
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.Flow

/**
 * What a page does with its internal state when it is resumed after being covered by another page
 * or by a modal interruption.
 */
enum class ResumeBehavior {
    /** Keeps the previous internal state, e.g. the cursor position of a list. */
    RETAIN,

    /** Rebuilds the page from scratch. */
    DISCARD
}

/**
 * A single screen of the projection engine: it renders itself as a frame and translates handlebar
 * presses into navigation actions.
 */
interface PageProvider {

    /** Stable identifier used in logs and in stack diagnostics. */
    val name: String

    val onResumeBehavior: ResumeBehavior

    fun render(requestId: Int): ProjectionFrame

    fun onInput(functionId: FunctionId): NavigationAction

    /**
     * Semantic twin of [render] for the in-app mirror. Built from the same state, at the same
     * instant, by the same instance: the mirror and the projected frame can never disagree.
     *
     * Deliberately without a default implementation: a page that forgets it does not compile, and a
     * page the mirror cannot reach is a function the rider has on the handlebar and not on the
     * phone.
     */
    fun mirror(): MirrorPage

    val invalidations: Flow<Unit>
}
