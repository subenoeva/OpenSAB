package com.opensab.sab.session.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BondStateTest {

    @Test
    fun mapsTheAndroidBondConstantsOntoTheEnum() {
        assertEquals(BondState.NONE, BondState.fromAndroidValue(10))
        assertEquals(BondState.BONDING, BondState.fromAndroidValue(11))
        assertEquals(BondState.BONDED, BondState.fromAndroidValue(12))
    }

    @Test
    fun treatsAnUnrecognisedBondConstantAsUnbonded() {
        // Anything the stack invents that we do not know about must not read as "already paired":
        // claiming a bond we do not have sends the handshake straight into GATT_AUTH_FAIL.
        assertEquals(BondState.NONE, BondState.fromAndroidValue(0))
        assertEquals(BondState.NONE, BondState.fromAndroidValue(99))
    }

    @Test
    fun describesTheDocumentedUnbondReasonsWithTheirSpecNames() {
        assertEquals("1 (UNBOND_REASON_AUTH_FAILED)", BondFailureReason.describe(1))
        assertEquals("3 (UNBOND_REASON_AUTH_CANCELED)", BondFailureReason.describe(3))
        assertEquals("6 (UNBOND_REASON_AUTH_TIMEOUT)", BondFailureReason.describe(6))
        assertEquals("9 (UNBOND_REASON_REMOVED)", BondFailureReason.describe(9))
    }

    @Test
    fun keepsTheRawNumberVisibleForAnUnknownUnbondReason() {
        assertEquals("42 (unknown)", BondFailureReason.describe(42))
    }

    @Test
    fun describesAMissingUnbondReasonWithoutInventingANumber() {
        // ACTION_BOND_STATE_CHANGED carries no reason extra when the bond was never attempted.
        assertEquals("no reason reported", BondFailureReason.describe(null))
    }
}
