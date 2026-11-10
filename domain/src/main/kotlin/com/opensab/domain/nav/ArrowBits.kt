package com.opensab.domain.nav

/**
 * A maneuver arrow reduced to 32×32 one-bit cells: cropped to its opaque pixels and
 * centred in a square, so where and how large the app drew it does not matter. Two drawings of the
 * same arrow land a few bits apart at most; two different arrows, 16 or more.
 */
class ArrowBits private constructor(private val rows: IntArray) {

    fun distanceTo(other: ArrowBits): Int = rows.indices.sumOf { Integer.bitCount(rows[it] xor other.rows[it]) }

    override fun equals(other: Any?): Boolean = other is ArrowBits && rows.contentEquals(other.rows)

    override fun hashCode(): Int = rows.contentHashCode()

    /** The arrow as 32 lines of `#` and `.`, for a log that has to show an arrow nobody knows yet. */
    override fun toString(): String =
        rows.joinToString("\n") { row -> (SIZE - 1 downTo 0).joinToString("") { if ((row shr it) and 1 == 1) "#" else "." } }

    companion object {
        const val SIZE = 32
        private const val OPAQUE_ALPHA = 128

        /** `null` when no pixel is opaque: there is no arrow to compare. */
        fun of(width: Int, height: Int, argb: IntArray): ArrowBits? {
            require(argb.size == width * height) { "argb holds ${argb.size} pixels, not $width×$height" }
            fun opaque(x: Int, y: Int) =
                x in 0 until width && y in 0 until height && (argb[y * width + x] ushr 24) >= OPAQUE_ALPHA

            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            for (y in 0 until height) for (x in 0 until width) if (opaque(x, y)) {
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
            if (maxX < 0) return null

            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1
            val side = maxOf(boxWidth, boxHeight)
            val originX = minX - (side - boxWidth) / 2
            val originY = minY - (side - boxHeight) / 2

            val rows = IntArray(SIZE) { cy ->
                val y0 = originY + cy * side / SIZE
                val y1 = maxOf(y0 + 1, originY + (cy + 1) * side / SIZE)
                (0 until SIZE).fold(0) { row, cx ->
                    val x0 = originX + cx * side / SIZE
                    val x1 = maxOf(x0 + 1, originX + (cx + 1) * side / SIZE)
                    var count = 0
                    for (y in y0 until y1) for (x in x0 until x1) if (opaque(x, y)) count++
                    (row shl 1) or (if (count * 2 >= (x1 - x0) * (y1 - y0)) 1 else 0)
                }
            }
            return ArrowBits(rows)
        }
    }
}
