package com.opensab.sab.protocol.assignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssignmentResultParserTest {

    @Test
    fun shouldParseAcceptedFunctionRegistrationWithoutErrorPosition() {
        val result = AssignmentResultParser.parse(byteArrayOf(0x01, 0x00, 0x09, 0x01))

        assertEquals(0x01, result.recordType)
        assertEquals(9, result.requestId)
        assertEquals(AssignmentStatus.SUCCESS, result.status)
        assertNull(result.errorPosition)
        assertTrue(result.status.isAccepted)
    }

    @Test
    fun shouldParseRequestIdAsBigEndianUint16() {
        val result = AssignmentResultParser.parse(byteArrayOf(0x02, 0x01, 0x2C, 0x01))

        assertEquals(300, result.requestId)
    }

    @Test
    fun shouldTreatAlreadyRegisteredAsAccepted() {
        val result = AssignmentResultParser.parse(byteArrayOf(0x01, 0x00, 0x03, 0x0D))

        assertEquals(AssignmentStatus.ALREADY_REGISTERED, result.status)
        assertTrue(result.status.isAccepted)
    }

    @Test
    fun shouldParseInvalidParametersWithErrorPosition() {
        val result = AssignmentResultParser.parse(byteArrayOf(0x02, 0x00, 0x0B, 0x0C, 0x04))

        assertEquals(AssignmentStatus.INVALID_PARAMETERS, result.status)
        assertEquals(4, result.errorPosition)
        assertFalse(result.status.isAccepted)
    }

    @Test
    fun shouldParseInvalidIdAsRejected() {
        val result = AssignmentResultParser.parse(byteArrayOf(0x02, 0x00, 0x0B, 0x0B))

        assertEquals(AssignmentStatus.INVALID_ID, result.status)
        assertFalse(result.status.isAccepted)
    }

    @Test
    fun shouldMapUnknownStatusCodeToUnknown() {
        val result = AssignmentResultParser.parse(byteArrayOf(0x01, 0x00, 0x01, 0x7F))

        assertEquals(AssignmentStatus.UNKNOWN, result.status)
        assertFalse(result.status.isAccepted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shouldRejectPayloadShorterThanFourBytes() {
        AssignmentResultParser.parse(byteArrayOf(0x01, 0x00, 0x01))
    }

    @Test(expected = IllegalArgumentException::class)
    fun shouldRejectPayloadLongerThanFiveBytes() {
        AssignmentResultParser.parse(byteArrayOf(0x01, 0x00, 0x01, 0x01, 0x00, 0x00))
    }

    @Test(expected = IllegalArgumentException::class)
    fun shouldRejectUnknownRecordType() {
        AssignmentResultParser.parse(byteArrayOf(0x07, 0x00, 0x01, 0x01))
    }

    @Test
    fun requestId16RoundTripsThroughToInt() {
        assertEquals(300, RequestId16.fromInt(300).toInt())
        assertEquals(1, RequestId16.fromInt(1).toInt())
        assertEquals(65535, RequestId16.fromInt(65535).toInt())
    }
}
