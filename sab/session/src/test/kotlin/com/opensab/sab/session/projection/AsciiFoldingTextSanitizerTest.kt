package com.opensab.sab.session.projection

import org.junit.Assert.assertEquals
import org.junit.Test

class AsciiFoldingTextSanitizerTest {

    private val sanitizer = AsciiFoldingTextSanitizer()

    @Test
    fun `folds latin accents to ascii base letters`() {
        val input = "Mötley Crüe — Canción Nº 1"
        val folded = sanitizer.toAscii(input)

        // Diacritics stripped, em-dash and special ordinal folded to ASCII-safe glyphs.
        assertEquals("Motley Crue - Cancion No 1", folded)
    }

    @Test
    fun `replaces unmapped non-latin characters with the fallback character`() {
        val thai = "เพลงไทย"
        val folded = sanitizer.toAscii(thai, fallbackChar = '?')

        // Thai does not decompose to Latin base characters via NFD, so every code point maps to ?.
        assertEquals("???????", folded)
    }

    @Test
    fun `never emits an empty string for non-empty input`() {
        val thai = "เพลงไทย"
        val sanitized = sanitizer.sanitize(thai, segmentDisplay = true)

        // ProjectionBuffer.putTlvText crashes if text is empty while ColorId is present, so the
        // sanitizer must NEVER drop all characters to the empty string.
        assertEquals("???????", sanitized)
    }

    @Test
    fun aColorClusterKeepsTheTextIntact() {
        val input = "Mötley Crüe — Canción Nº 1"
        val sanitized = sanitizer.sanitize(input, segmentDisplay = false)

        assertEquals(input, sanitized)
    }

    @Test
    fun aSegmentClusterFoldsToAscii() =
        assertEquals("Cancion 24C", sanitizer.sanitize("Canción 24°C", segmentDisplay = true))

    /**
     * U+00B0 is neither a diacritic nor a mapped glyph: without a replacement an indicator cluster
     * would read `24?C`.
     */
    @Test
    fun `folds the degree sign away instead of turning it into a question mark`() {
        assertEquals("24C", sanitizer.toAscii("24°C"))
        assertEquals("-3F Snow showers", sanitizer.toAscii("-3°F Snow showers"))
        assertEquals("24C", sanitizer.sanitize("24°C", segmentDisplay = true))
    }

    /**
     * A replacement that deletes characters can empty a non-empty string, and
     * `ProjectionBuffer.putTlvText` crashes on an empty text with a ColorId present.
     */
    @Test
    fun `never folds a non-empty string down to nothing`() {
        assertEquals("?", sanitizer.toAscii("°"))
        assertEquals("?", sanitizer.sanitize("°°°", segmentDisplay = true))
    }

    @Test
    fun `preserves empty input without padding`() {
        assertEquals("", sanitizer.toAscii(""))
        assertEquals("", sanitizer.sanitize("", segmentDisplay = true))
        assertEquals("", sanitizer.sanitize("", segmentDisplay = false))
    }
}
