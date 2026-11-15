package com.opensab.sab.protocol.assignment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AssignmentRecordEncodingTest {

    // lo=0x34, hi=0x12 -> wire order: 0x12, 0x34
    private val testRequestId = RequestId16(lo = 0x34.toByte(), hi = 0x12.toByte())

    @Test
    fun encodeFunctionRecordProducesExact5BytesMatchingGoldenVectorG1() {
        val record = AssignmentRecord.Function(
            requestId = testRequestId,
            functionId = FunctionId.VOLUME_UP, // 5 (0x05)
            mode = 0x02
        )
        val encoded = record.encode()

        assertEquals(5, encoded.size)
        assertArrayEquals(
            byteArrayOf(0x01, 0x12, 0x34, 0x05, 0x02),
            encoded
        )
    }

    @Test
    fun encodeFunctionHidVolumeUpProducesExact8BytesMatchingGoldenVectorG2() {
        val record = AssignmentRecord.FunctionHid(
            requestId = testRequestId,
            functionId = FunctionId.VOLUME_UP, // 5 (0x05)
            usagePage = 0x07,
            usage = 0x80.toByte(),
            trailer = 0x00
        )
        val encoded = record.encode()

        assertEquals(8, encoded.size)
        assertArrayEquals(
            byteArrayOf(0x01, 0x12, 0x34, 0x05, 0x01, 0x07, 0x80.toByte(), 0x00),
            encoded
        )
    }

    @Test
    fun encodeFunctionHidVolumeDownProducesExact8BytesMatchingGoldenVectorG3() {
        val record = AssignmentRecord.FunctionHid(
            requestId = testRequestId,
            functionId = FunctionId.VOLUME_DOWN, // 6 (0x06)
            usagePage = 0x07,
            usage = 0x81.toByte(),
            trailer = 0x00
        )
        val encoded = record.encode()

        assertEquals(8, encoded.size)
        assertArrayEquals(
            byteArrayOf(0x01, 0x12, 0x34, 0x06, 0x01, 0x07, 0x81.toByte(), 0x00),
            encoded
        )
    }

    @Test
    fun encodeColorRecordProducesExact7BytesMatchingGoldenVectorG4() {
        val record = AssignmentRecord.Color(
            requestId = testRequestId,
            colorId = 2, // NEGATIVE
            r = 0xD6,
            g = 0x47,
            b = 0x47
        )
        val encoded = record.encode()

        assertEquals(7, encoded.size)
        assertArrayEquals(
            byteArrayOf(0x02, 0x12, 0x34, 0x02, 0xD6.toByte(), 0x47, 0x47),
            encoded
        )
    }

    @Test
    fun encodeEndAssignmentSentinelProducesSingleByte0x03() {
        val record = AssignmentRecord.EndAssignment
        val encoded = record.encode()

        assertEquals(1, encoded.size)
        assertArrayEquals(byteArrayOf(0x03), encoded)
    }

    /** Golden vector G6: a non-zero high byte goes first, so 0x0100 is 01 00 and never 00 01. */
    @Test
    fun encodeFunctionRecordGoldenVectorG6HighByteFirst() {
        val record = AssignmentRecord.Function(requestId = RequestId16.fromInt(0x0100), functionId = FunctionId.VOLUME_UP)
        assertArrayEquals(byteArrayOf(0x01, 0x01, 0x00, 0x05, 0x02), record.encode())
    }

    /** Golden vector G7: a low byte of 0xFF survives as one byte, after a zero high byte. */
    @Test
    fun encodeFunctionRecordGoldenVectorG7LowByteAtItsMaximum() {
        val record = AssignmentRecord.Function(requestId = RequestId16.fromInt(0x00FF), functionId = FunctionId.VOLUME_UP)
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0x05, 0x02), record.encode())
    }

    /** Golden vector G5, the trap: every record type writes the id high-then-low at bytes 1 and 2. */
    @Test
    fun everyRecordWithAnIdWritesItHighByteFirstGoldenVectorG5() {
        val id = RequestId16.fromInt(0x1234)
        val records = listOf(
            AssignmentRecord.Function(requestId = id, functionId = FunctionId.VOLUME_UP),
            AssignmentRecord.FunctionHid(requestId = id, functionId = FunctionId.VOLUME_DOWN),
            AssignmentRecord.Color(requestId = id, colorId = 2, r = 0xD6, g = 0x47, b = 0x47)
        )
        records.forEach { record ->
            val encoded = record.encode()
            assertEquals(record.toString(), 0x12.toByte(), encoded[1])
            assertEquals(record.toString(), 0x34.toByte(), encoded[2])
        }
    }
}
