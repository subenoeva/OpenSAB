package com.opensab.sab.protocol.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FirmwareVersionParserTest {

    @Test
    fun parseStandardTwoByteVersion() {
        val payload = byteArrayOf(0x01, 0x04)
        val version = FirmwareVersionParser.parse(payload)

        assertNotNull(version)
        assertEquals("01", version!!.major)
        assertEquals("04", version.minor)
        assertEquals("01.04", version.versionString)
    }

    @Test
    fun parseVersionWithSingleDigitHexFormat() {
        val payload = byteArrayOf(0x0A, 0x0C)
        val version = FirmwareVersionParser.parse(payload)

        assertNotNull(version)
        assertEquals("10", version!!.major)
        assertEquals("12", version.minor)
        assertEquals("10.12", version.versionString)
    }

    @Test
    fun parseEmptyOrInvalidPayloadReturnsNull() {
        assertNull(FirmwareVersionParser.parse(null))
        assertNull(FirmwareVersionParser.parse(byteArrayOf()))
        assertNull(FirmwareVersionParser.parse(byteArrayOf(0x01)))
    }
}
