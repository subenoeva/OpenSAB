package com.opensab.sab.protocol.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptParserTest {

    @Test
    fun parseGoldenVectorG5OkReceipt() {
        val payload = byteArrayOf(0x01, 0x01, 0x01)
        val receipt = ReceiptParser.parse(payload)

        assertEquals(CommandId.MYSTERY_BOX, receipt.commandId)
        assertEquals(0x01, receipt.requestId)
        assertEquals(ReceiptStatus.SUCCESS, receipt.status)
        assertNull(receipt.errorPosition)
        assertTrue(receipt.isSuccess)
    }

    @Test
    fun parseLegacyOkReceiptWithZeroStatus() {
        val payload = byteArrayOf(0x01, 0x01, 0x00)
        val receipt = ReceiptParser.parse(payload)

        assertEquals(CommandId.MYSTERY_BOX, receipt.commandId)
        assertEquals(0x01, receipt.requestId)
        assertEquals(ReceiptStatus.SUCCESS_ALT, receipt.status)
        assertNull(receipt.errorPosition)
        assertTrue(receipt.isSuccess)
    }

    @Test
    fun parseGoldenVectorG6ErrorReceiptWithPosition() {
        val payload = byteArrayOf(0x01, 0x01, 0x0C, 0x04)
        val receipt = ReceiptParser.parse(payload)

        assertEquals(CommandId.MYSTERY_BOX, receipt.commandId)
        assertEquals(0x01, receipt.requestId)
        assertEquals(ReceiptStatus.INVALID_PARAMETERS, receipt.status)
        assertEquals(4, receipt.errorPosition)
        assertFalse(receipt.isSuccess)
    }

    @Test
    fun parseRequestSabModeReceipt() {
        val payload = byteArrayOf(0x0A, 0x05, 0x01)
        val receipt = ReceiptParser.parse(payload)

        assertEquals(CommandId.REQUEST_SAB_MODE, receipt.commandId)
        assertEquals(0x05, receipt.requestId)
        assertEquals(ReceiptStatus.SUCCESS, receipt.status)
        assertNull(receipt.errorPosition)
        assertTrue(receipt.isSuccess)
    }

    @Test
    fun parseTooSmallPayloadThrowsIllegalArgumentException() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ReceiptParser.parse(byteArrayOf(0x01, 0x01))
        }
        assertTrue(
            "Exception message must mention 'too small'",
            exception.message?.contains("too small", ignoreCase = true) == true
        )
    }

    @Test
    fun parseTooBigPayloadThrowsIllegalArgumentException() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ReceiptParser.parse(byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00))
        }
        assertTrue(
            "Exception message must mention 'too big'",
            exception.message?.contains("too big", ignoreCase = true) == true
        )
    }

    @Test
    fun parseInvalidCommandIdThrowsIllegalArgumentException() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ReceiptParser.parse(byteArrayOf(0x7F, 0x01, 0x00))
        }
        assertTrue(
            "Exception message must mention receipt id or command validity",
            exception.message?.contains("not valid", ignoreCase = true) == true ||
                exception.message?.contains("invalid", ignoreCase = true) == true
        )
    }

    @Test
    fun receiptsAreEqualIfAndOnlyIfRequestIdMatches() {
        val r1 = Receipt(CommandId.MYSTERY_BOX, 5, ReceiptStatus.SUCCESS)
        val r2 = Receipt(CommandId.POP_UP, 5, ReceiptStatus.INVALID_PARAMETERS, errorPosition = 4)
        val r3 = Receipt(CommandId.MYSTERY_BOX, 6, ReceiptStatus.SUCCESS)

        assertEquals(r1, r2)
        assertFalse(r1 == r3)
        assertEquals(r1.hashCode(), r2.hashCode())
    }
}
