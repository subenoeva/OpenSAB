package com.opensab.app.ble

import com.opensab.sab.session.ble.BondEvent
import com.opensab.sab.session.ble.BondState
import com.opensab.sab.session.ble.GattStatus

/**
 * Reads `ACTION_BOND_STATE_CHANGED` once its extras have been pulled out of the `Intent`.
 *
 * Taking an `Intent` apart needs a device, but deciding what its extras *mean* does not, so that
 * decision lives here where a JVM test can reach it — the same split [BondState] and [GattStatus]
 * already draw around `android.bluetooth`. [AndroidBondController] is left with nothing but the
 * three extras and a log line.
 *
 * Every path that discards a broadcast returns `null` from one place, which is what makes the
 * adapter able to say out loud that it discarded one. A receiver that goes quiet on an unexpected
 * shape is indistinguishable from a receiver that was never called.
 */
object BondBroadcast {

    /** The action itself, restated so the pure side can recognise it without `BluetoothDevice`. */
    const val ACTION_BOND_STATE_CHANGED = "android.bluetooth.device.action.BOND_STATE_CHANGED"

    /**
     * `BluetoothDevice.EXTRA_REASON` is not in the public SDK, but the broadcast carries it and it
     * is the only thing that says *why* a bond was refused. The name is stable.
     */
    const val EXTRA_REASON = "android.bluetooth.device.extra.REASON"

    /** Sentinel for "the reason extra was absent", distinct from every real `UNBOND_REASON_*`. */
    const val NO_REASON: Int = Int.MIN_VALUE

    /**
     * Turns one broadcast into a [BondEvent], or `null` if it says nothing about a bond.
     *
     * [macAddress] is nullable because it comes out of a parcelable extra the framework is free to
     * decline to hand over; an event with no address matches no caller anyway.
     */
    fun eventFrom(
        action: String?,
        macAddress: String?,
        bondStateValue: Int,
        reasonValue: Int
    ): BondEvent? {
        if (action != ACTION_BOND_STATE_CHANGED) return null
        if (macAddress.isNullOrBlank()) return null

        return BondEvent(
            macAddress = macAddress,
            state = BondState.fromAndroidValue(bondStateValue),
            reason = reasonValue.takeIf { it != NO_REASON }
        )
    }
}
