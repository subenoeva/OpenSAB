package com.opensab.sab.session.ble

import app.cash.turbine.test
import com.opensab.logging.Log
import com.opensab.logging.LogSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bring-up: connect, then MTU, then discovery, then ready. `lost()` is accepted from anywhere and
 * is terminal until the next `connecting()`; every other transition only from the state before it.
 */
class GattLinkTest {

    private enum class Step { DISCONNECTED, CONNECTING, REQUESTING_MTU, DISCOVERING, CONNECTED }

    private fun linkAt(step: Step, mtu: Int = 247): GattLink = GattLink().apply {
        if (step == Step.DISCONNECTED) return@apply
        connecting()
        if (step == Step.CONNECTING) return@apply
        requestingMtu()
        if (step == Step.REQUESTING_MTU) return@apply
        mtuNegotiated(mtu)
        discoveringServices()
        if (step == Step.DISCOVERING) return@apply
        ready()
    }

    private fun assertRejected(link: GattLink, transition: GattLink.() -> Boolean) {
        val before = link.state.value
        val mtuBefore = link.negotiatedMtu
        assertFalse(link.transition())
        assertEquals(before, link.state.value)
        assertEquals(mtuBefore, link.negotiatedMtu)
    }

    // region Accepted

    @Test
    fun startsDisconnectedAtTheDefaultAttMtu() {
        val link = GattLink()

        assertEquals(BleConnectionState.Disconnected, link.state.value)
        assertEquals(23, link.negotiatedMtu)
        assertFalse(link.isConnected)
    }

    @Test
    fun walksTheBringUpInOrder() {
        val link = GattLink()

        assertTrue(link.connecting())
        assertEquals(BleConnectionState.Connecting, link.state.value)
        assertTrue(link.requestingMtu())
        assertEquals(BleConnectionState.RequestingMtu, link.state.value)
        assertTrue(link.mtuNegotiated(247))
        assertTrue(link.discoveringServices())
        assertEquals(BleConnectionState.DiscoveringServices, link.state.value)
        assertTrue(link.ready())

        assertEquals(BleConnectionState.Connected(247), link.state.value)
        assertEquals(247, link.negotiatedMtu)
        assertTrue(link.isConnected)
    }

    @Test
    fun aFailedMtuNegotiationLeavesTheLinkOnTheDefault() {
        // If the negotiation fails, the link keeps the ATT default of 23.
        val link = GattLink()
        link.connecting()
        link.requestingMtu()
        link.discoveringServices()
        link.ready()

        assertEquals(BleConnectionState.Connected(23), link.state.value)
    }

    @Test
    fun isLostFromEveryState() {
        Step.entries.forEach { step ->
            val link = linkAt(step)
            assertTrue("lost() from $step", link.lost())
            assertEquals(BleConnectionState.Disconnected, link.state.value)
        }
    }

    @Test
    fun reconnectsAfterALossFromTheDefaultMtu() {
        val link = linkAt(Step.CONNECTED)
        link.lost()

        assertTrue(link.connecting())
        assertEquals(BleConnectionState.Connecting, link.state.value)
        assertEquals(23, link.negotiatedMtu)
    }

    @Test
    fun aRetriedMtuWhileConnectedCountsWithoutReEmittingTheState() = runTest {
        val link = linkAt(Step.CONNECTED, mtu = 23)

        link.state.test {
            assertEquals(BleConnectionState.Connected(23), awaitItem())

            assertTrue(link.mtuNegotiated(247))

            assertEquals(247, link.negotiatedMtu)
            expectNoEvents()
        }
    }

    @Test
    fun aRetriedMtuWhileConnectedIsNotARejectedTransition() {
        // The adapter's requestMtu() in Connected only arms the operation; its callback records the
        // result through mtuNegotiated() and must not leave a warning on every retry.
        val warnings = mutableListOf<String?>()
        val sink = LogSink { priority, _, message, _ -> if (priority == Log.WARN) warnings += message }
        val link = linkAt(Step.CONNECTED, mtu = 23)
        Log.install(sink)
        try {
            assertTrue(link.mtuNegotiated(247))
        } finally {
            Log.uninstall(sink)
        }

        assertEquals(emptyList<String?>(), warnings)
    }

    // endregion

    // region connecting() only from Disconnected

    @Test
    fun rejectsConnectingWhileConnecting() = assertRejected(linkAt(Step.CONNECTING)) { connecting() }

    @Test
    fun rejectsConnectingWhileRequestingMtu() = assertRejected(linkAt(Step.REQUESTING_MTU)) { connecting() }

    @Test
    fun rejectsConnectingWhileDiscovering() = assertRejected(linkAt(Step.DISCOVERING)) { connecting() }

    @Test
    fun rejectsConnectingWhileConnected() = assertRejected(linkAt(Step.CONNECTED)) { connecting() }

    // endregion

    // region requestingMtu() only from Connecting

    @Test
    fun rejectsRequestingMtuWhileDisconnected() = assertRejected(linkAt(Step.DISCONNECTED)) { requestingMtu() }

    @Test
    fun rejectsRequestingMtuTwice() = assertRejected(linkAt(Step.REQUESTING_MTU)) { requestingMtu() }

    @Test
    fun rejectsRequestingMtuWhileDiscovering() = assertRejected(linkAt(Step.DISCOVERING)) { requestingMtu() }

    @Test
    fun rejectsRequestingMtuWhileConnected() = assertRejected(linkAt(Step.CONNECTED)) { requestingMtu() }

    // endregion

    // region mtuNegotiated() only in RequestingMtu or Connected

    @Test
    fun rejectsAnMtuWhileDisconnected() = assertRejected(linkAt(Step.DISCONNECTED)) { mtuNegotiated(247) }

    @Test
    fun rejectsAnMtuWhileConnecting() = assertRejected(linkAt(Step.CONNECTING)) { mtuNegotiated(247) }

    @Test
    fun rejectsAnMtuWhileDiscovering() = assertRejected(linkAt(Step.DISCOVERING, mtu = 185)) { mtuNegotiated(247) }

    @Test
    fun rejectsAnMtuAfterTheLinkIsLost() {
        val link = linkAt(Step.CONNECTED, mtu = 185)
        link.lost()

        assertRejected(link) { mtuNegotiated(247) }
    }

    // endregion

    // region discoveringServices() only from RequestingMtu

    @Test
    fun rejectsDiscoveringWhileDisconnected() = assertRejected(linkAt(Step.DISCONNECTED)) { discoveringServices() }

    @Test
    fun rejectsDiscoveringWhileConnecting() = assertRejected(linkAt(Step.CONNECTING)) { discoveringServices() }

    @Test
    fun rejectsDiscoveringTwice() = assertRejected(linkAt(Step.DISCOVERING)) { discoveringServices() }

    @Test
    fun rejectsDiscoveringWhileConnected() = assertRejected(linkAt(Step.CONNECTED)) { discoveringServices() }

    // endregion

    // region ready() only from DiscoveringServices

    @Test
    fun neverAnnouncesConnectedOverALostLink() {
        // The case that decides the rule: a ready() that slips in after lost() would announce
        // Connected over a dead link, which is worse than losing the transition.
        val link = linkAt(Step.DISCOVERING)
        link.lost()

        assertRejected(link) { ready() }
    }

    @Test
    fun rejectsReadyWhileConnecting() = assertRejected(linkAt(Step.CONNECTING)) { ready() }

    @Test
    fun rejectsReadyWhileRequestingMtu() = assertRejected(linkAt(Step.REQUESTING_MTU)) { ready() }

    @Test
    fun rejectsReadyTwice() = assertRejected(linkAt(Step.CONNECTED)) { ready() }

    // endregion
}
