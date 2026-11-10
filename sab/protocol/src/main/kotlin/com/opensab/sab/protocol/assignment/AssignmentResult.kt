package com.opensab.sab.protocol.assignment

/** The BTU's answer to one assignment record, notified on AssignmentControl. */
data class AssignmentResult(
    val recordType: Int,
    val requestId: Int,
    val status: AssignmentStatus,
    val errorPosition: Int? = null
) {
    // Keyed on requestId ONLY: copy/componentN/toString still span all four fields, but equality
    // ignores status/errorPosition. Never assertEquals two results expecting field-wise equality.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssignmentResult) return false
        return requestId == other.requestId
    }

    override fun hashCode(): Int = requestId.hashCode()
}

/** Wire layout: `[recordType, requestIdHi, requestIdLo, status, errorPosition?]`, 4 or 5 bytes. */
object AssignmentResultParser {

    fun parse(payload: ByteArray): AssignmentResult {
        if (payload.size < 4) {
            throw IllegalArgumentException("Unexpected AssignmentResult | It's too small: size=${payload.size}")
        }
        if (payload.size > 5) {
            throw IllegalArgumentException("Unexpected AssignmentResult | It's too big: size=${payload.size}")
        }

        val recordType = payload[0].toInt() and 0xFF
        val isKnownRecord = recordType == AssignmentRecord.RECORD_FUNCTION.toInt() ||
            recordType == AssignmentRecord.RECORD_COLOR.toInt()
        if (!isKnownRecord) {
            throw IllegalArgumentException(
                "Unexpected AssignmentResult | Record type not valid: 0x%02X".format(recordType)
            )
        }

        val requestId = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        val status = AssignmentStatus.fromCode(payload[3].toInt() and 0xFF)
        val errorPosition = if (payload.size == 5) payload[4].toInt() and 0xFF else null

        return AssignmentResult(recordType, requestId, status, errorPosition)
    }
}
