package com.opensab.sab.protocol.ble.cts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** Payloads of the Bluetooth SIG Current Time Service (0x1805). */
object CtsData {

    /**
     * Builds the 10-byte payload for Current Time characteristic (0x2A2B).
     * Format:
     * - Year: Int16 (LE)
     * - Month: Int8 (1..12)
     * - Day: Int8 (1..31)
     * - Hours: Int8 (0..23)
     * - Minutes: Int8 (0..59)
     * - Seconds: Int8 (0..59)
     * - DayOfWeek: Int8 (1..7, 1=Monday)
     * - Fractions256: Int8 (1/256th of a second)
     * - AdjustReason: Int8
     */
    fun buildCurrentTimePayload(
        dateTime: LocalDateTime = LocalDateTime.now(),
        adjustReason: Byte = 0
    ): ByteArray {
        val fraction = ((dateTime.nano / 1_000_000L) * 256 / 1000).toInt().coerceIn(0, 255)

        return ByteBuffer.allocate(10)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(dateTime.year.toShort())
            .put(dateTime.monthValue.toByte())
            .put(dateTime.dayOfMonth.toByte())
            .put(dateTime.hour.toByte())
            .put(dateTime.minute.toByte())
            .put(dateTime.second.toByte())
            .put(dateTime.dayOfWeek.value.toByte())
            .put(fraction.toByte())
            .put(adjustReason)
            .array()
    }

    /**
     * Builds the 2-byte payload for Local Time Information characteristic (0x2A0F): the current
     * UTC offset in 15-minute steps, summer time included, and a DST byte that is always 0. The
     * cluster expects exactly that; a DST of 4 on top would put it an hour ahead.
     */
    fun buildLocalTimeInfoPayload(
        zonedDateTime: ZonedDateTime = ZonedDateTime.now()
    ): ByteArray {
        val timeZone15MinUnits = zonedDateTime.offset.totalSeconds / 60 / 15

        return ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(timeZone15MinUnits.toByte())
            .put(0)
            .array()
    }
}
