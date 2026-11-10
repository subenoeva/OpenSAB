package com.opensab.sab.protocol.vehicle

import com.opensab.logging.Log

/**
 * Handlebar switchgear variant reported by `SwitchType` (`ddd14e97-636d-4338-a798-94cf7ce13667`).
 * The names say nothing about how many directions the switchgear has because the protocol does
 * not either.
 */
enum class SwitchType(val code: Int) {
    RESERVED(0x00),
    TYPE_A(0x01),
    TYPE_B(0x02),

    /** The wire sentinel is the byte -1; the parser widens it to an unsigned int. */
    UNKNOWN(0xFF);

    companion object {
        fun fromCode(code: Int): SwitchType = entries.find { it.code == code }
            ?: UNKNOWN.also { Log.w("Unknown SwitchType ID with value: %d", code) }
    }
}
