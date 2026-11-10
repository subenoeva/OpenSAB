package com.opensab.sab.protocol.assignment

/** Handlebar functions, numbered as the FunctionNotification byte carries them. */
enum class FunctionId(val value: Int) {
    PREVIOUS(1),
    NEXT(2),
    BACK(3),
    SELECT(4),
    VOLUME_UP(5),
    VOLUME_DOWN(6),
    VOLUME_MUTE(7),
    MENU(8),
    MODE_SAB(9),
    MODE_HU(10);

    companion object {
        fun fromValue(value: Int): FunctionId? = entries.find { it.value == value }
    }
}
