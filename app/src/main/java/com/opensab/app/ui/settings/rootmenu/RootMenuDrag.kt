package com.opensab.app.ui.settings.rootmenu

import kotlin.math.roundToInt

/**
 * The index a row dragged from [from] by [offsetPx] lands on, with rows [rowHeightPx] tall.
 *
 * A row changes place once the finger has carried it past half of its neighbour. A row not yet
 * measured stays where it is rather than jumping on a division by zero.
 */
fun dragTargetIndex(from: Int, offsetPx: Float, rowHeightPx: Float, count: Int): Int {
    if (rowHeightPx <= 0f || count <= 0) return from
    val shift = (offsetPx / rowHeightPx).roundToInt()
    return (from + shift).coerceIn(0, count - 1)
}
