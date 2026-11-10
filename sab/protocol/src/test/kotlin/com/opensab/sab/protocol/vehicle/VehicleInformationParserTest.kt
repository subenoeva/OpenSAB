package com.opensab.sab.protocol.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleInformationParserTest {

    @Test
    fun switchTypeFromCodeMappings() {
        assertEquals(SwitchType.TYPE_A, SwitchType.fromCode(0x01))
        assertEquals(SwitchType.TYPE_B, SwitchType.fromCode(0x02))
        assertEquals(SwitchType.UNKNOWN, SwitchType.fromCode(0x03))
        assertEquals(SwitchType.UNKNOWN, SwitchType.fromCode(0x99))
    }

    @Test
    fun destinationIdFromCodeMappings() {
        assertEquals(DestinationId.EU, DestinationId.fromCode(0x01))
        assertEquals(DestinationId.GERMANY, DestinationId.fromCode(0x02))
        assertEquals(DestinationId.FRANCE, DestinationId.fromCode(0x03))
        assertEquals(DestinationId.JAPAN, DestinationId.fromCode(0x06))
        assertEquals(DestinationId.UNKNOWN, DestinationId.fromCode(0x77))
    }

    @Test
    fun parseSeriesCode_realFieldTelemetryCb650r_returns0536() {
        // Read from a CB650R 2024: [0x36, 0x05], uint16 little-endian 0x0536 -> "0536".
        val bytes = byteArrayOf(0x36, 0x05)
        val seriesCode = VehicleInfoParsers.parseSeriesCode(bytes)
        assertEquals("0536", seriesCode)
    }

    @Test
    fun parseSeriesCode_cb350Pair_returns052B() {
        // CB350's series/model pair is ("K3RA", "052B").
        // uint16 little-endian for 0x052B is bytes [0x2B, 0x05].
        val bytes = byteArrayOf(0x2B, 0x05)
        val seriesCode = VehicleInfoParsers.parseSeriesCode(bytes)
        assertEquals("052B", seriesCode)
    }

    @Test
    fun parseSeriesCode_invalidLengthsAndNull_returnsEmptyString() {
        assertEquals("", VehicleInfoParsers.parseSeriesCode(null))
        assertEquals("", VehicleInfoParsers.parseSeriesCode(byteArrayOf()))
        assertEquals("", VehicleInfoParsers.parseSeriesCode(byteArrayOf(0x36)))
        assertEquals("", VehicleInfoParsers.parseSeriesCode(byteArrayOf(0x36, 0x05, 0x01)))
    }

    @Test
    fun parseSeriesCode_formatsAsFourUppercaseHexDigits() {
        assertEquals("000A", VehicleInfoParsers.parseSeriesCode(byteArrayOf(0x0A, 0x00)))
        assertEquals("0FFF", VehicleInfoParsers.parseSeriesCode(byteArrayOf(0xFF.toByte(), 0x0F.toByte())))
        assertEquals("BEEF", VehicleInfoParsers.parseSeriesCode(byteArrayOf(0xEF.toByte(), 0xBE.toByte())))
    }
}
