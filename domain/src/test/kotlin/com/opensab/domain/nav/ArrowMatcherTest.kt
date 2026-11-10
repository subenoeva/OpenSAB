package com.opensab.domain.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrowMatcherTest {

    private val opaque = 0xFF000000.toInt()

    private fun bits(vararg rects: IntArray): ArrowBits {
        val argb = IntArray(126 * 126)
        rects.forEach { (x, y, w, h) ->
            for (py in y until y + h) for (px in x until x + w) argb[py * 126 + px] = opaque
        }
        return ArrowBits.of(126, 126, argb)!!
    }

    private val bar = bits(intArrayOf(50, 10, 20, 100))
    private val ell = bits(intArrayOf(10, 10, 10, 60), intArrayOf(10, 60, 40, 10))
    private val tee = bits(intArrayOf(10, 10, 100, 20), intArrayOf(50, 10, 20, 100))

    @Test
    fun theClosestReferenceWithinTheThresholdWins() {
        assertEquals(
            ArrowMatch("ell", 0),
            ArrowMatcher.closest(ell, mapOf("bar" to bar, "ell" to ell, "tee" to tee))
        )
    }

    @Test
    fun nothingWithinTheThresholdIsNoMatchButStillHasANearest() {
        val references = mapOf("bar" to bar, "tee" to tee)

        assertNull(ArrowMatcher.closest(ell, references))
        val nearest = ArrowMatcher.nearest(ell, references)!!
        assertTrue(nearest.name in references.keys)
        assertTrue(nearest.distance > ArrowMatcher.MAX_DISTANCE)
    }

    /** Identical drawings (a ramp and its turn) tie; the result must not depend on map order. */
    @Test
    fun aTieGoesToTheAlphabeticallyFirstName() {
        assertEquals(
            ArrowMatch("maneuver_on_ramp_normal_left", 0),
            ArrowMatcher.closest(ell, linkedMapOf("maneuver_turn_normal_left" to ell, "maneuver_on_ramp_normal_left" to ell))
        )
    }
}
