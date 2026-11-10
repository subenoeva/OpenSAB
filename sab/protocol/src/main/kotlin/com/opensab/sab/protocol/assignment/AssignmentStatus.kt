package com.opensab.sab.protocol.assignment

enum class AssignmentStatus(val code: Int) {
    SUCCESS(0x01),
    INVALID_ID(0x0B),
    INVALID_PARAMETERS(0x0C),
    ALREADY_REGISTERED(0x0D),
    UNKNOWN(0xFF);

    /** The BTU holds the record: either freshly registered or already present from a prior session. */
    val isAccepted: Boolean get() = this == SUCCESS || this == ALREADY_REGISTERED

    companion object {
        fun fromCode(code: Int): AssignmentStatus = entries.find { it.code == code } ?: UNKNOWN
    }
}
