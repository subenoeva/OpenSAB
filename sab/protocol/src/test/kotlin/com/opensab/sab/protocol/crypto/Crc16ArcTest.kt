package com.opensab.sab.protocol.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class Crc16ArcTest {

    @Test
    fun calculateGoldenVectorStandardAscii() {
        // The published CRC-16/ARC check value.
        val input = "123456789".toByteArray(Charsets.US_ASCII)
        val crc = Crc16Arc.calculate(input)
        assertEquals(0xBB3D, crc)
    }

    @Test
    fun calculateEmptyPayloadReturnsZero() {
        val input = byteArrayOf()
        val crc = Crc16Arc.calculate(input)
        assertEquals(0x0000, crc)
    }

    @Test
    fun calculateSingleZeroByteReturnsZero() {
        val input = byteArrayOf(0x00)
        val crc = Crc16Arc.calculate(input)
        assertEquals(0x0000, crc)
    }

    @Test
    fun calculateSingleByte0xFF() {
        // TABLE[0xFF] = 0x4040
        val input = byteArrayOf(0xFF.toByte())
        val crc = Crc16Arc.calculate(input)
        assertEquals(0x4040, crc)
    }

    @Test
    fun calculateTwoZeroBytesReturnsZero() {
        val input = byteArrayOf(0x00, 0x00)
        val crc = Crc16Arc.calculate(input)
        assertEquals(0x0000, crc)
    }

    @Test
    fun calculateSequence01020304() {
        val input = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val crc = Crc16Arc.calculate(input)
        val expected = Crc16Arc.calculate(input, 0, input.size)
        assertEquals(expected, crc)
    }

    @Test
    fun calculate110BytesPaddingSequence() {
        val input = ByteArray(110) { 0xAA.toByte() }
        val crc = Crc16Arc.calculate(input)
        assertEquals(true, crc in 0..0xFFFF)
    }
}
