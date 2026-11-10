package com.opensab.domain.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An arrow with no match is logged as ASCII next to its nearest reference, **once
 * for each different arrow in a row**, so a ride of the same unknown maneuver does not flood the log.
 */
class ArrowRecognizerTest {

    private val opaque = 0xFF000000.toInt()

    private fun bits(vararg rects: IntArray): ArrowBits {
        val argb = IntArray(126 * 126)
        rects.forEach { (x, y, w, h) ->
            for (py in y until y + h) for (px in x until x + w) argb[py * 126 + px] = opaque
        }
        return ArrowBits.of(126, 126, argb)!!
    }

    private val depart = bits(intArrayOf(50, 10, 20, 100))
    private val unknownEll = bits(intArrayOf(10, 10, 10, 60), intArrayOf(10, 60, 40, 10))
    private val unknownTee = bits(intArrayOf(10, 10, 100, 20), intArrayOf(50, 10, 20, 100))

    private var catalogReads = 0
    private val recognizer = ArrowRecognizer {
        catalogReads++
        mapOf("maneuver_depart" to depart)
    }

    @Test
    fun aKnownArrowIsNamedWithItsDistance() {
        assertEquals(
            ArrowRecognition.Known(ArrowMatch("maneuver_depart", 0)),
            recognizer.recognize(depart)
        )
    }

    @Test
    fun anUnknownArrowIsReportedOnceWhileItRepeats() {
        val first = recognizer.recognize(unknownEll)
        val second = recognizer.recognize(unknownEll)

        assertEquals(
            ArrowRecognition.Unknown(unknownEll, ArrowMatcher.nearest(unknownEll, mapOf("maneuver_depart" to depart))),
            first
        )
        assertEquals(ArrowRecognition.UnknownAgain, second)
    }

    @Test
    fun aDifferentUnknownArrowIsReportedAgain() {
        recognizer.recognize(unknownEll)

        assertEquals(unknownTee, (recognizer.recognize(unknownTee) as ArrowRecognition.Unknown).bits)
    }

    @Test
    fun aKnownArrowInBetweenEndsTheRun() {
        recognizer.recognize(unknownEll)
        recognizer.recognize(depart)

        assertEquals(unknownEll, (recognizer.recognize(unknownEll) as ArrowRecognition.Unknown).bits)
    }

    /** Pinned as it was in the listener: a notification with no arrow does not end the run. */
    @Test
    fun aNotificationWithoutArrowDoesNotEndTheRun() {
        recognizer.recognize(unknownEll)

        assertEquals(ArrowRecognition.NoArrow, recognizer.recognize(null))
        assertEquals(ArrowRecognition.UnknownAgain, recognizer.recognize(unknownEll))
    }

    @Test
    fun noArrowNeverReadsTheCatalog() {
        recognizer.recognize(null)

        assertEquals(0, catalogReads)
    }
}
