package com.opensab.sab.protocol.assignment

/**
 * Outcome of one full assignment sequence, so the caller decides what a partial registration means
 * without re-reading the log.
 */
data class AssignmentReport(
    val written: Int,
    val accepted: Int,
    val rejected: List<AssignmentResult>,
    val timedOut: List<Int>
) {
    val isClean: Boolean get() = rejected.isEmpty() && timedOut.isEmpty()
}
