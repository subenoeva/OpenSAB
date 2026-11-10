package com.opensab.sab.session.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceBonderTest {

    private val mac = "02:00:00:00:00:AB"

    @Test
    fun leavesAnAlreadyBondedDeviceAlone() = runTest {
        val controller = FakeBondController(initialState = BondState.BONDED)
        val bonder = DefaultDeviceBonder(controller)

        assertTrue(bonder.ensureBonded(mac).isSuccess)
        assertEquals(
            "Re-bonding a paired motorcycle would drop the link key it already trusts",
            0,
            controller.createBondCalls
        )
    }

    @Test
    fun pairsAnUnbondedDeviceAndWaitsForTheStackToConfirm() = runTest {
        val controller = FakeBondController(initialState = BondState.NONE)
        controller.onCreateBond = {
            controller.emit(BondEvent(mac, BondState.BONDING))
            controller.emit(BondEvent(mac, BondState.BONDED))
        }
        val bonder = DefaultDeviceBonder(controller)

        assertTrue(bonder.ensureBonded(mac).isSuccess)
        assertEquals(1, controller.createBondCalls)
    }

    @Test
    fun waitsOutAPairingThatIsAlreadyInFlightInsteadOfStartingASecondOne() = runTest {
        val controller = FakeBondController(initialState = BondState.BONDING)
        val bonder = DefaultDeviceBonder(controller)

        val pending = async { bonder.ensureBonded(mac) }
        runCurrent()
        controller.emit(BondEvent(mac, BondState.BONDED))

        assertTrue(pending.await().isSuccess)
        assertEquals(0, controller.createBondCalls)
    }

    @Test
    fun reportsTheDecodedReasonWhenTheStackDropsTheBond() = runTest {
        val controller = FakeBondController(initialState = BondState.NONE)
        controller.onCreateBond = {
            controller.emit(BondEvent(mac, BondState.BONDING))
            controller.emit(BondEvent(mac, BondState.NONE, reason = 1))
        }
        val bonder = DefaultDeviceBonder(controller)

        val failure = bonder.ensureBonded(mac).exceptionOrNull()
        assertTrue(failure is BondFailedException)
        assertEquals(1, (failure as BondFailedException).reason)
        assertTrue(
            "Expected the AOSP reason name in the message, got: ${failure.message}",
            failure.message!!.contains("1 (UNBOND_REASON_AUTH_FAILED)")
        )
    }

    @Test
    fun failsWhenTheStackRefusesToEvenStartPairing() = runTest {
        val controller = FakeBondController(initialState = BondState.NONE, createBondAccepted = false)
        val bonder = DefaultDeviceBonder(controller)

        val failure = bonder.ensureBonded(mac).exceptionOrNull()
        assertTrue(failure is BondFailedException)
        assertTrue(
            "Expected the message to say the stack refused, got: ${failure!!.message}",
            failure.message!!.contains("refused")
        )
    }

    @Test
    fun ignoresBondEventsBelongingToAnotherDevice() = runTest {
        val controller = FakeBondController(initialState = BondState.NONE)
        controller.onCreateBond = {
            controller.emit(BondEvent("AA:BB:CC:DD:EE:FF", BondState.NONE, reason = 1))
            controller.emit(BondEvent(mac, BondState.BONDED))
        }
        val bonder = DefaultDeviceBonder(controller)

        assertTrue(bonder.ensureBonded(mac).isSuccess)
    }

    @Test
    fun givesUpWhenTheStackNeverReportsATerminalBondState() = runTest {
        val controller = FakeBondController(initialState = BondState.NONE)
        val bonder = DefaultDeviceBonder(controller, timeout = 30.seconds)

        val failure = bonder.ensureBonded(mac).exceptionOrNull()
        assertTrue("Expected a bond timeout, got: $failure", failure is BondTimedOutException)
        assertEquals(30.seconds, (failure as BondTimedOutException).timeout)
        assertEquals(mac, failure.macAddress)
    }

    @Test
    fun listensBeforeItAsksTheStackToPair() = runTest {
        // The BONDED broadcast can land before createBond() has even returned. A bonder that
        // subscribes afterwards misses it and hangs until the watchdog fires.
        val controller = FakeBondController(initialState = BondState.NONE)
        controller.onCreateBond = { controller.emit(BondEvent(mac, BondState.BONDED)) }
        val bonder = DefaultDeviceBonder(controller, timeout = 30.seconds)

        assertTrue(bonder.ensureBonded(mac).isSuccess)
    }

    /** Records what the bonder asked the platform to do and scripts what the stack answers. */
    private class FakeBondController(
        initialState: BondState,
        private val createBondAccepted: Boolean = true
    ) : BondController {

        var state: BondState = initialState
        var createBondCalls: Int = 0
        var onCreateBond: () -> Unit = {}

        // No replay: an event emitted before the bonder subscribes is lost, exactly as a
        // broadcast is lost when no receiver is registered yet.
        private val events = MutableSharedFlow<BondEvent>(extraBufferCapacity = 8)
        override val bondEvents: SharedFlow<BondEvent> = events

        override fun bondState(macAddress: String): BondState = state

        override fun createBond(macAddress: String): Boolean {
            createBondCalls++
            if (createBondAccepted) onCreateBond()
            return createBondAccepted
        }

        fun emit(event: BondEvent) {
            state = event.state
            check(events.tryEmit(event)) { "Fake bond event buffer overflowed" }
        }
    }
}
