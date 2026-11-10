package com.opensab.app.ble

import com.opensab.sab.session.ble.BondEvent
import com.opensab.sab.session.ble.BondState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BondBroadcastTest {

    @Test
    fun readsABondTransitionOutOfTheBroadcastExtras() {
        val event = BondBroadcast.eventFrom(
            action = BondBroadcast.ACTION_BOND_STATE_CHANGED,
            macAddress = MAC,
            bondStateValue = ANDROID_BOND_BONDED,
            reasonValue = BondBroadcast.NO_REASON
        )

        assertEquals(BondEvent(MAC, BondState.BONDED, reason = null), event)
    }

    @Test
    fun keepsTheUnbondReasonTheStackReports() {
        // A refused bond is the only case that carries EXTRA_REASON, and it is the only thing
        // that says why: dropping it would leave the UI with nothing to tell the rider.
        val event = BondBroadcast.eventFrom(
            action = BondBroadcast.ACTION_BOND_STATE_CHANGED,
            macAddress = MAC,
            bondStateValue = ANDROID_BOND_NONE,
            reasonValue = UNBOND_REASON_AUTH_CANCELED
        )

        assertEquals(BondEvent(MAC, BondState.NONE, UNBOND_REASON_AUTH_CANCELED), event)
    }

    @Test
    fun ignoresABroadcastForAnotherAction() {
        val event = BondBroadcast.eventFrom(
            action = "android.bluetooth.device.action.ACL_CONNECTED",
            macAddress = MAC,
            bondStateValue = ANDROID_BOND_BONDED,
            reasonValue = BondBroadcast.NO_REASON
        )

        assertNull(event)
    }

    @Test
    fun ignoresABroadcastThatNamesNoDevice() {
        // The device extra is a parcelable the framework can decline to hand over; an event with
        // no address matches no caller and would only ever be dropped downstream.
        listOf(null, "", "   ").forEach { address ->
            val event = BondBroadcast.eventFrom(
                action = BondBroadcast.ACTION_BOND_STATE_CHANGED,
                macAddress = address,
                bondStateValue = ANDROID_BOND_BONDED,
                reasonValue = BondBroadcast.NO_REASON
            )

            assertNull("An address of ${address?.let { "'$it'" }} must not become an event", event)
        }
    }

    private companion object {
        const val MAC = "02:00:00:00:00:AB"
        const val ANDROID_BOND_NONE = 10
        const val ANDROID_BOND_BONDED = 12
        const val UNBOND_REASON_AUTH_CANCELED = 3
    }
}
