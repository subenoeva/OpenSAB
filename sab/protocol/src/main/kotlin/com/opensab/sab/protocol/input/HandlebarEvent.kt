package com.opensab.sab.protocol.input

import com.opensab.sab.protocol.assignment.FunctionId

/** Display mode of the instrument cluster. */
enum class DashboardMode {
    /** The cluster's own screens; it boots here and discards every projection. */
    HU_MODE,

    /** The cluster renders projected frames and reports handlebar presses. */
    SAB_MODE
}

/**
 * Where a press came from, for the log and diagnostics only. **`BaseStateManager` never reads it**:
 * if it did there would be two dispatch paths, and the app and the cluster could disagree.
 */
enum class InputSource { HANDLEBAR, MIRROR }

sealed interface HandlebarEvent {

    val timestamp: Long

    /**
     * Handlebar button press (codes 1..8), or the synthetic twin of one emitted by the in-app
     * mirror.
     */
    data class ButtonPressed(
        val functionId: FunctionId,
        val source: InputSource = InputSource.HANDLEBAR,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HandlebarEvent

    /** The cluster switched display mode (codes 9 and 10). */
    data class ModeChanged(
        val mode: DashboardMode,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HandlebarEvent
}
