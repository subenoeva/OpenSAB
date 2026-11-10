package com.opensab.sab.session.projection

import java.text.Normalizer
import java.util.regex.Pattern

/**
 * An interface rather than a static object so that an implementation backed by
 * `android.icu.text.Transliterator` (romanizing Thai, Korean, Lao) can replace this default when a
 * motorcycle is bonded that needs it.
 */
fun interface TextSanitizer {
    /** [segmentDisplay] false means a colour cluster, or none identified yet: leave text as is. */
    fun sanitize(text: String, segmentDisplay: Boolean): String
}

/**
 * Strips diacritics and maps non-ASCII characters to standard Latin equivalents for segment / LCD
 * motorcycle clusters that lack fonts for Unicode scripts.
 *
 * JVM-pure: uses [java.text.Normalizer] so every degradation is unit-testable without Robolectric.
 *
 * Deliberately outside the Hilt graph: callers depend on [TextSanitizer], and `provideTextSanitizer`
 * is the single binding. An `@Inject constructor` here would add a second one for the concrete
 * type, and the two would hand out different instances.
 */
class AsciiFoldingTextSanitizer : TextSanitizer {

    override fun sanitize(text: String, segmentDisplay: Boolean): String {
        if (!segmentDisplay || text.isEmpty()) return text
        return toAscii(text)
    }

    fun toAscii(text: String, fallbackChar: Char = '?'): String {
        if (text.isEmpty()) return text

        val replaced = buildString(text.length) {
            for (ch in text) {
                val direct = DIRECT_REPLACEMENTS[ch]
                if (direct != null) append(direct) else append(ch)
            }
        }

        val normalized = Normalizer.normalize(replaced, Normalizer.Form.NFD)
        val stripped = DIACRITICS.matcher(normalized).replaceAll("")

        val ascii = buildString(stripped.length) {
            for (ch in stripped) {
                if (ch.code in 32..126 || ch == '\n' || ch == '\r' || ch == '\t') {
                    append(ch)
                } else {
                    append(fallbackChar)
                }
            }
        }

        // A deleting replacement (the degree sign) can empty a non-empty string, and
        // ProjectionBuffer.putTlvText crashes on empty text with a ColorId present.
        return ascii.ifEmpty { fallbackChar.toString() }
    }

    companion object {
        private val DIACRITICS: Pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+")

        private val DIRECT_REPLACEMENTS = mapOf(
            '—' to "-",
            '–' to "-",
            '―' to "-",
            '«' to "\"",
            '»' to "\"",
            '“' to "\"",
            '”' to "\"",
            '‘' to "'",
            '’' to "'",
            // A temperature reads `24C` on a segment cluster, never `24?C`: the degree sign is not
            // a diacritic, so without this it would fall through to the fallback character.
            '°' to "",
            'º' to "o",
            'ª' to "a",
            '№' to "No",
            '…' to "..."
        )
    }
}
