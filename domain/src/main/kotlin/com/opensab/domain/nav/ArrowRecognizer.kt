package com.opensab.domain.nav

/** What [ArrowRecognizer] made of one notification's large icon. */
sealed interface ArrowRecognition {

    /** The icon could not be drawn, or had nothing opaque in it. */
    data object NoArrow : ArrowRecognition

    data class Known(val match: ArrowMatch) : ArrowRecognition

    /** The first of a run of this arrow: worth logging, with the reference [nearest] to it. */
    data class Unknown(val bits: ArrowBits, val nearest: ArrowMatch?) : ArrowRecognition

    /** The same unknown arrow as the last one reported: already in the log. */
    data object UnknownAgain : ArrowRecognition
}

/**
 * Names the arrow of each navigation notification against [references], reporting an arrow with no
 * match once for each different one in a row, so a ride through the same unknown maneuver logs it once.
 *
 * **Owner of [lastUnknown]:** this instance, and one instance serves one listener. Not thread-safe:
 * the listener calls it from the main thread only.
 */
class ArrowRecognizer(private val references: () -> Map<String, ArrowBits>) {

    private var lastUnknown: ArrowBits? = null

    fun recognize(bits: ArrowBits?): ArrowRecognition {
        if (bits == null) return ArrowRecognition.NoArrow
        val known = references()
        ArrowMatcher.closest(bits, known)?.let {
            lastUnknown = null
            return ArrowRecognition.Known(it)
        }
        if (bits == lastUnknown) return ArrowRecognition.UnknownAgain
        lastUnknown = bits
        return ArrowRecognition.Unknown(bits, ArrowMatcher.nearest(bits, known))
    }
}
