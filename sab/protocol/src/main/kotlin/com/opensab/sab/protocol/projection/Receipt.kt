package com.opensab.sab.protocol.projection

enum class CommandId(val value: Int) {
    MYSTERY_BOX(0x01),
    POP_UP(0x02),
    ACTIVE_INDICATORS_CONTROL(0x03),
    REQUEST_SAB_MODE(0x0A),
    FIRMWARE_UPDATE(0x0B);

    companion object {
        fun fromValue(value: Int): CommandId? = entries.find { it.value == value }
    }
}

enum class BodyType(val value: Int) {
    MENU(0x01),
    LIVE_SCREEN(0x02)
}

enum class ReceiptStatus(val code: Int) {
    SUCCESS(0x01),
    SUCCESS_ALT(0x00),
    INVALID_COMMAND(0x0B),
    INVALID_PARAMETERS(0x0C),
    ALREADY_REGISTERED(0x0D),
    UNKNOWN(0xFF);

    companion object {
        fun fromCode(code: Int): ReceiptStatus = entries.find { it.code == code } ?: UNKNOWN
    }
}

/**
 * The BTU's answer to a frame, notified on the DataCommunication ControlPoint. Two receipts are
 * equal when their [requestId] matches, whatever the rest.
 */
data class Receipt(
    val commandId: CommandId,
    val requestId: Int,
    val status: ReceiptStatus,
    val errorPosition: Int? = null
) {
    val isSuccess: Boolean get() = status == ReceiptStatus.SUCCESS || status == ReceiptStatus.SUCCESS_ALT

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Receipt) return false
        return requestId == other.requestId
    }

    override fun hashCode(): Int = requestId.hashCode()
}

object ReceiptParser {

    fun parse(payload: ByteArray): Receipt {
        if (payload.size < 3) {
            throw IllegalArgumentException("Unexpected Receipt | It's too small: size=${payload.size}")
        }
        if (payload.size > 4) {
            throw IllegalArgumentException("Unexpected Receipt | It's too big: size=${payload.size}")
        }

        val cmdRaw = payload[0].toInt() and 0xFF
        val cmdId = CommandId.fromValue(cmdRaw)
            ?: throw IllegalArgumentException("Unexpected Receipt | Receipt Id not valid: 0x%02X".format(cmdRaw))

        val reqId = payload[1].toInt() and 0xFF
        val statusCode = payload[2].toInt() and 0xFF
        val status = ReceiptStatus.fromCode(statusCode)

        val errorPosition = if (payload.size == 4) payload[3].toInt() and 0xFF else null

        return Receipt(
            commandId = cmdId,
            requestId = reqId,
            status = status,
            errorPosition = errorPosition
        )
    }
}
