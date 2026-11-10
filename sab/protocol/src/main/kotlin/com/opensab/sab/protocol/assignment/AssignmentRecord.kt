package com.opensab.sab.protocol.assignment

/** 16-bit request id of an assignment record, big-endian on the wire. */
data class RequestId16(val lo: Byte, val hi: Byte) {
    /** The two bytes in wire order, high first; kept as the record of it, the encoders write them inline. */
    @Suppress("unused")
    fun toBytesOnWire(): ByteArray = byteArrayOf(hi, lo)

    /** Correlates a written record with the AssignmentResult the BTU sends back. */
    fun toInt(): Int = ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)

    companion object {
        fun fromInt(id: Int): RequestId16 {
            val lo = (id and 0xFF).toByte()
            val hi = ((id ushr 8) and 0xFF).toByte()
            return RequestId16(lo, hi)
        }
    }
}

sealed interface AssignmentRecord {

    /** Correlates the record with its AssignmentResult; null for control records. */
    val requestId: RequestId16?

    fun encode(): ByteArray

    /** Function record, 5 bytes: `[0x01, reqIdHi, reqIdLo, functionId, mode (0x02)]`. */
    data class Function(
        override val requestId: RequestId16,
        val functionId: FunctionId,
        val mode: Byte = MODE_DEFAULT
    ) : AssignmentRecord {
        override fun encode(): ByteArray = byteArrayOf(
            RECORD_FUNCTION,
            requestId.hi,
            requestId.lo,
            functionId.value.toByte(),
            mode
        )
    }

    /**
     * Function record with a HID usage, 8 bytes:
     * `[0x01, reqIdHi, reqIdLo, functionId, 0x01, usagePage (0x07), usage, 0x00]`.
     */
    data class FunctionHid(
        override val requestId: RequestId16,
        val functionId: FunctionId,
        val usagePage: Byte = HID_PAGE_KEYBOARD,
        val usage: Byte = HID_USAGE_VOLUME_UP,
        val trailer: Byte = 0x00
    ) : AssignmentRecord {
        override fun encode(): ByteArray = byteArrayOf(
            RECORD_FUNCTION,
            requestId.hi,
            requestId.lo,
            functionId.value.toByte(),
            MODE_HID,
            usagePage,
            usage,
            trailer
        )
    }

    /** Palette slot record, 7 bytes: `[0x02, reqIdHi, reqIdLo, colorId, R, G, B]`. */
    data class Color(
        override val requestId: RequestId16,
        val colorId: Int,
        val r: Int,
        val g: Int,
        val b: Int
    ) : AssignmentRecord {
        override fun encode(): ByteArray = byteArrayOf(
            RECORD_COLOR,
            requestId.hi,
            requestId.lo,
            (colorId and 0xFF).toByte(),
            (r and 0xFF).toByte(),
            (g and 0xFF).toByte(),
            (b and 0xFF).toByte()
        )
    }

    /** End-of-batch sentinel, the single byte `0x03`. */
    data object EndAssignment : AssignmentRecord {
        override val requestId: RequestId16? = null
        override fun encode(): ByteArray = byteArrayOf(0x03)
    }

    companion object {
        const val RECORD_FUNCTION: Byte = 0x01
        const val RECORD_COLOR: Byte = 0x02
        const val MODE_DEFAULT: Byte = 0x02
        const val MODE_HID: Byte = 0x01
        const val HID_PAGE_KEYBOARD: Byte = 0x07
        const val HID_USAGE_VOLUME_UP: Byte = 0x80.toByte()
        const val HID_USAGE_VOLUME_DOWN: Byte = 0x81.toByte()
    }
}

