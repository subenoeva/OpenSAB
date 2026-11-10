package com.opensab.domain.audio

import kotlinx.coroutines.flow.Flow

/** Whether an output worn by the rider (Bluetooth A2DP or SCO, wired, USB, BLE headset) is connected. */
fun interface HeadsetPresence {
    /** The current state first, then every change. */
    fun connected(): Flow<Boolean>
}
