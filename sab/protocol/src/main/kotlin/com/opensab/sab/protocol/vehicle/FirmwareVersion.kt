package com.opensab.sab.protocol.vehicle

import java.util.Locale

/** The 2-byte firmware version exposed by DFUService. */
data class FirmwareVersion(
    val major: String,
    val minor: String
) {
    val versionString: String get() = "$major.$minor"
}

object FirmwareVersionParser {

    fun parse(bytes: ByteArray?): FirmwareVersion? {
        if (bytes == null || bytes.size < 2) return null
        val majorInt = bytes[0].toInt() and 0xFF
        val minorInt = bytes[1].toInt() and 0xFF
        val majorStr = String.format(Locale.US, "%02d", majorInt)
        val minorStr = String.format(Locale.US, "%02d", minorInt)
        return FirmwareVersion(major = majorStr, minor = minorStr)
    }
}
