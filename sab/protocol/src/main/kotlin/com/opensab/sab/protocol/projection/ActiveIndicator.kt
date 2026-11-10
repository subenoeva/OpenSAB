package com.opensab.sab.protocol.projection

/**
 * Permanent status icons of the cluster top bar, carried as a bitmask by
 * `ACTIVE_INDICATORS_CONTROL` (0x03).
 */
enum class ActiveIndicator(val bit: Int) {
    PHONE(0x01),
    NAVIGATION(0x02),
    MUSIC(0x04),
    MESSAGE(0x08),
    MUTE(0x10),
    NO_NETWORK(0x20);

    companion object {

        /** OR-combines [indicators] into the single bitmask the frame carries. */
        fun maskOf(indicators: Collection<ActiveIndicator>): Int =
            indicators.fold(0) { mask, indicator -> mask or indicator.bit }
    }
}
