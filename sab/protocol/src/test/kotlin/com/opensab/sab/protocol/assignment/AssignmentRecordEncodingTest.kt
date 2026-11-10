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
}

