package com.opensab.sab.protocol.projection

import com.opensab.sab.protocol.assignment.ColorId
import java.io.ByteArrayOutputStream

object ProjectionBuffer {

    const val MAX_TEXT_BYTES: Int = 30
    const val MAX_POPUP_TEXT_BYTES: Int = 90

    /**
     * Writes the length alone (0x00) when [text] is empty/null (colorId must be null),
     * otherwise length + truncated UTF-8 bytes + colorId.id (colorId must NOT be null).
     */
    fun ByteArrayOutputStream.putTlvText(text: String?, colorId: ColorId?, maxBytes: Int) {
        if (text.isNullOrEmpty()) {
            require(colorId == null) { "ColorId must be null when text is empty" }
            write(0x00)
        } else {
            require(colorId != null) { "ColorId must not be null when text is present" }
            val truncatedBytes = truncateUtf8(text, maxBytes)
            write(truncatedBytes.size and 0xFF)
            write(truncatedBytes)
            write(colorId.id and 0xFF)
        }
    }

    /**
     * Writes nothing when [id] is null: the field disappears from the frame. A 0x00 in its place
     * shifts every later offset and the BTU rejects the frame.
     */
    fun ByteArrayOutputStream.putIdOrOmit(id: Int?) {
        if (id != null) {
            write(id and 0xFF)
        }
    }

    fun ByteArrayOutputStream.putIdOrZero(id: Int?) {
        if (id != null) {
            write(id and 0xFF)
        } else {
            write(0x00)
        }
    }

    /** Truncates by Unicode code points so no multi-byte character is cut in half. */
    fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        var byteCount = 0
        var charIndex = 0
        while (charIndex < text.length) {
            val codePoint = text.codePointAt(charIndex)
            val codePointByteLen = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (byteCount + codePointByteLen > maxBytes) {
                break
            }
            byteCount += codePointByteLen
            charIndex += Character.charCount(codePoint)
        }
        return text.substring(0, charIndex).toByteArray(Charsets.UTF_8)
    }

    /** Present when non-null and different from 0 (IC_NONE). */
    fun isPresent(id: Int?): Boolean = id != null && id != 0

    fun isPresent(iconId: IconId?): Boolean = iconId != null && iconId != IconId.IC_NONE
}
