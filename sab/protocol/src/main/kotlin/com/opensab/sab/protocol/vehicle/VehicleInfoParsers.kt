package com.opensab.sab.protocol.vehicle

import java.util.Locale

object VehicleInfoParsers {

    fun parseSingleByte(bytes: ByteArray?): Int {
        if (bytes == null || bytes.isEmpty()) return 0
        return bytes[0].toInt() and 0xFF
    }

    /**
     * The series code is a little-endian 16-bit value: `[0x36, 0x05]` is series "0536". Anything
     * but exactly 2 bytes gives an empty string.
     */
    fun parseSeriesCode(bytes: ByteArray?): String {
        if (bytes == null || bytes.size != 2) return ""
        val value = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        return String.format(Locale.US, "%04X", value)
    }
}

