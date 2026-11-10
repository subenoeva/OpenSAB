package com.opensab.sab.protocol.projection

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.ProjectionBuffer.putIdOrOmit
import com.opensab.sab.protocol.projection.ProjectionBuffer.putIdOrZero
import com.opensab.sab.protocol.projection.ProjectionBuffer.putTlvText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ProjectionBufferTest {

    @Test
    fun shouldWriteSingleZeroByteWhenTextIsEmpty() {
        val out = ByteArrayOutputStream()
        out.putTlvText(text = "", colorId = null, maxBytes = ProjectionBuffer.MAX_TEXT_BYTES)
        assertArrayEquals(byteArrayOf(0x00), out.toByteArray())

        val outNull = ByteArrayOutputStream()
        outNull.putTlvText(text = null, colorId = null, maxBytes = ProjectionBuffer.MAX_TEXT_BYTES)
        assertArrayEquals(byteArrayOf(0x00), outNull.toByteArray())
    }

    @Test
    fun shouldThrowWhenTextIsNotEmptyAndColorIdIsNull() {
        val out = ByteArrayOutputStream()
        val exception = assertThrows(IllegalArgumentException::class.java) {
            out.putTlvText(text = "Hola", colorId = null, maxBytes = ProjectionBuffer.MAX_TEXT_BYTES)
        }
        assertTrue(
            "Exception message must mention colorId requirement",
            exception.message?.contains("color", ignoreCase = true) == true
        )
    }

    @Test
    fun shouldThrowWhenTextIsEmptyAndColorIdIsNotNull() {
        val out = ByteArrayOutputStream()
        val exception = assertThrows(IllegalArgumentException::class.java) {
            out.putTlvText(text = "", colorId = ColorId.PURE_WHITE, maxBytes = ProjectionBuffer.MAX_TEXT_BYTES)
        }
        assertTrue(
            "Exception message must mention colorId must be null for empty text",
            exception.message?.contains("color", ignoreCase = true) == true
        )
    }

    @Test
    fun shouldWriteLenTextAndColorWhenTextIsPresent() {
        val out = ByteArrayOutputStream()
        out.putTlvText(text = "Menu", colorId = ColorId.PURE_WHITE, maxBytes = ProjectionBuffer.MAX_TEXT_BYTES)
        val expected = byteArrayOf(0x04, 0x4D, 0x65, 0x6E, 0x75, 0x00)
        assertArrayEquals(expected, out.toByteArray())
    }

    @Test
    fun shouldWriteNoBytesWhenOmittableIdIsNull() {
        val out = ByteArrayOutputStream()
        out.putIdOrOmit(null)
        assertEquals(0, out.toByteArray().size)
    }

    @Test
    fun shouldWriteOneByteWhenOmittableIdIsPresent() {
        val out = ByteArrayOutputStream()
        out.putIdOrOmit(0x05)
        assertArrayEquals(byteArrayOf(0x05), out.toByteArray())
    }

    @Test
    fun shouldWriteExplicitZeroWhenSentinelIdIsNull() {
        val out = ByteArrayOutputStream()
        out.putIdOrZero(null)
        assertArrayEquals(byteArrayOf(0x00), out.toByteArray())
    }

    @Test
    fun shouldWriteOneByteWhenSentinelIdIsPresent() {
        val out = ByteArrayOutputStream()
        out.putIdOrZero(0x07)
        assertArrayEquals(byteArrayOf(0x07), out.toByteArray())
    }

    @Test
    fun shouldNotSplitMultiByteCodePointWhenTruncating() {
        // 20 times 'ñ' (each 2 bytes UTF-8), total 40 bytes. With maxBytes=30 -> 15 chars = 30 bytes
        val textSpan = "ñ".repeat(20)
        val truncated = ProjectionBuffer.truncateUtf8(textSpan, 30)
        assertEquals(30, truncated.size)
        assertEquals("ñ".repeat(15), String(truncated, Charsets.UTF_8))

        // 8 emoji music notes 🎵 (each 4 bytes UTF-8), total 32 bytes. With maxBytes=30 -> 7 chars = 28 bytes
        val emojis = "🎵".repeat(8)
        val truncatedEmojis = ProjectionBuffer.truncateUtf8(emojis, 30)
        assertEquals(28, truncatedEmojis.size)
        assertEquals("🎵".repeat(7), String(truncatedEmojis, Charsets.UTF_8))
    }

    @Test
    fun shouldReportPresenceCorrectly() {
        assertFalse(ProjectionBuffer.isPresent(null as Int?))
        assertFalse(ProjectionBuffer.isPresent(null as IconId?))
        assertFalse(ProjectionBuffer.isPresent(0))
        assertFalse(ProjectionBuffer.isPresent(IconId.IC_NONE))
        assertTrue(ProjectionBuffer.isPresent(1))
        assertTrue(ProjectionBuffer.isPresent(IconId.DA_TURN_RIGHT))
        assertTrue(ProjectionBuffer.isPresent(255))
    }
}
