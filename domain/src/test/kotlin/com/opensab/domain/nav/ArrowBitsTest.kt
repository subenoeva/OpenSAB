package com.opensab.domain.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrowBitsTest {

    private val opaque = 0xFF000000.toInt()

    /** A canvas with the given `(x, y, width, height)` rectangles painted opaque. */
    private fun canvas(size: Int, vararg rects: IntArray, color: Int = opaque): IntArray {
        val argb = IntArray(size * size)
        rects.forEach { (x, y, w, h) ->
            for (py in y until y + h) for (px in x until x + w) argb[py * size + px] = color
        }
        return argb
    }

    /** An "L": a tall bar with a foot to the right. */
    private fun ell(x: Int, y: Int, scale: Int = 1) = arrayOf(
        intArrayOf(x, y, 10 * scale, 60 * scale),
        intArrayOf(x, y + 50 * scale, 40 * scale, 10 * scale)
    )

    @Test
    fun anEmptyIconHasNoArrow() {
        assertNull(ArrowBits.of(126, 126, IntArray(126 * 126)))
    }

    @Test
    fun halfTransparentPixelsCountAsEmpty() {
        assertNull(ArrowBits.of(126, 126, canvas(126, *ell(10, 10), color = 0x7F000000)))
    }

    @Test
    fun theSameShapeMovedGivesTheSameBits() {
        val a = ArrowBits.of(126, 126, canvas(126, *ell(10, 10)))!!
        val b = ArrowBits.of(126, 126, canvas(126, *ell(60, 40)))!!

        assertEquals(0, a.distanceTo(b))
    }

    @Test
    fun theSameShapeAtDoubleSizeStaysWithinTheThreshold() {
        val small = ArrowBits.of(126, 126, canvas(126, *ell(10, 10)))!!
        val large = ArrowBits.of(252, 252, canvas(252, *ell(20, 20, scale = 2)))!!

        assertTrue(small.distanceTo(large) <= ArrowMatcher.MAX_DISTANCE)
    }

    @Test
    fun aMirroredShapeIsFartherThanTheThreshold() {
        val ell = ArrowBits.of(126, 126, canvas(126, *ell(10, 10)))!!
        val mirrored = ArrowBits.of(
            126, 126,
            canvas(126, intArrayOf(40, 10, 10, 60), intArrayOf(10, 60, 40, 10))
        )!!

        assertTrue(ell.distanceTo(mirrored) > ArrowMatcher.MAX_DISTANCE)
    }
}
