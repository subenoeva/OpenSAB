package com.opensab.domain.nav

data class ArrowMatch(val name: String, val distance: Int)

/**
 * Picks the reference drawing an arrow is. Measured on Maps 26.38: the arrow Maps
 * posts is one of its drawings at 0 bits, and two different drawings are 16 bits apart or more.
 */
object ArrowMatcher {

    const val MAX_DISTANCE = 8

    fun closest(arrow: ArrowBits, references: Map<String, ArrowBits>): ArrowMatch? =
        nearest(arrow, references)?.takeIf { it.distance <= MAX_DISTANCE }

    /** No threshold: for logging how far an unknown arrow is from everything known. */
    fun nearest(arrow: ArrowBits, references: Map<String, ArrowBits>): ArrowMatch? =
        references.entries
            .map { (name, bits) -> ArrowMatch(name, arrow.distanceTo(bits)) }
            .minWithOrNull(compareBy<ArrowMatch> { it.distance }.thenBy { it.name })
}
