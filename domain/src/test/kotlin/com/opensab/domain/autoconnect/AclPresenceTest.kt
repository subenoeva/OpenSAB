package com.opensab.domain.autoconnect

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AclPresenceTest {

    private val mac = "02:00:00:00:00:AB"
    private val presence = AclPresence()

    @Test
    fun nothingIsLinkedUntilABroadcastSaysSo() = runTest {
        assertEquals(false, presence.linked(mac).first())
    }

    @Test
    fun aclConnectedLinksTheDeviceAndAclDisconnectedUnlinksIt() = runTest {
        presence.linked(mac).test {
            assertEquals(false, awaitItem())

            presence.record(AutoConnectEvaluator.ACTION_ACL_CONNECTED, mac)
            assertEquals(true, awaitItem())

            presence.record(AutoConnectEvaluator.ACTION_ACL_DISCONNECTED, mac)
            assertEquals(false, awaitItem())
        }
    }

    @Test
    fun theAddressIsMatchedWhateverItsCase() = runTest {
        presence.record(AutoConnectEvaluator.ACTION_ACL_CONNECTED, mac.lowercase())

        assertEquals(true, presence.linked(mac).first())
    }

    /** The headset coming and going must not read as the motorcycle doing so. */
    @Test
    fun anotherDeviceDoesNotMoveThisOne() = runTest {
        presence.record(AutoConnectEvaluator.ACTION_ACL_CONNECTED, mac)

        presence.linked(mac).test {
            assertEquals(true, awaitItem())
            presence.record(AutoConnectEvaluator.ACTION_ACL_DISCONNECTED, "02:00:00:00:00:CD")
            presence.record(AutoConnectEvaluator.ACTION_ACL_CONNECTED, "02:00:00:00:00:CD")
            expectNoEvents()
        }
    }

    @Test
    fun anUnrelatedActionChangesNothing() = runTest {
        presence.record(AutoConnectEvaluator.ACTION_ACL_CONNECTED, mac)
        presence.record("android.bluetooth.device.action.BOND_STATE_CHANGED", mac)

        assertEquals(true, presence.linked(mac).first())
    }
}
