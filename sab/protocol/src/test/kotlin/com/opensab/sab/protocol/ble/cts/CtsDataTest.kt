package com.opensab.sab.protocol.ble.cts

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CtsDataTest {

    @Test
    fun buildCurrentTimePayloadProducesExact10BytesWithCorrectLittleEndianFields() {
        val dt = LocalDateTime.of(2026, 8, 27, 12, 30, 45, 500_000_000)
        val payload = CtsData.buildCurrentTimePayload(dt, adjustReason = 1)

        assertEquals(10, payload.size)

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2026.toShort(), buf.short) // Year
        assertEquals(8.toByte(), buf.get()) // Month
        assertEquals(27.toByte(), buf.get()) // Day
        assertEquals(12.toByte(), buf.get()) // Hour
        assertEquals(30.toByte(), buf.get()) // Minute
        assertEquals(45.toByte(), buf.get()) // Second
        assertEquals(4.toByte(), buf.get()) // DayOfWeek (Thursday = 4)
        assertEquals(128.toByte(), buf.get()) // Fractions256 (500ms -> 128)
        assertEquals(1.toByte(), buf.get()) // AdjustReason (Manual = 1)
    }

    @Test
    fun buildLocalTimeInfoPayloadProducesExact2Bytes() {
        val zoneId = ZoneId.of("UTC+02:00")
        val zdt = ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, zoneId)
        val payload = CtsData.buildLocalTimeInfoPayload(zdt)

        assertEquals(2, payload.size)
        // UTC+2 = +8 in 15-minute intervals
        assertEquals(8.toByte(), payload[0])
    }

    /**
     * Summer time is folded into the zone byte and the DST byte is always 0. Adding a
     * DST of +1 h on top of an offset that already includes it would put the cluster an hour ahead.
     */
    @Test
    fun localTimeInfoFoldsSummerTimeIntoTheZoneAndSendsNoDst() {
        val summer = ZonedDateTime.of(2026, 7, 15, 12, 0, 0, 0, ZoneId.of("Europe/Madrid"))

        val payload = CtsData.buildLocalTimeInfoPayload(summer)

        assertEquals("UTC+2 in 15-minute steps", 8.toByte(), payload[0])
        assertEquals("DST is already in the zone byte", 0.toByte(), payload[1])
    }

    @Test
    fun localTimeInfoInWinterSendsTheStandardOffsetAndNoDst() {
        val winter = ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, ZoneId.of("Europe/Madrid"))

        val payload = CtsData.buildLocalTimeInfoPayload(winter)

        assertEquals(4.toByte(), payload[0])
        assertEquals(0.toByte(), payload[1])
    }
}
