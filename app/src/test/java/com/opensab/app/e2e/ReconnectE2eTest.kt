package com.opensab.app.e2e

import com.opensab.app.ui.MainDispatcherRule
import com.opensab.domain.autoconnect.AutoConnectEvaluator
import com.opensab.sab.protocol.projection.CommandId
import com.opensab.sab.session.ConnectionStep
import com.opensab.sab.session.ReconnectPolicy
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.ble.BleDisconnectedException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The retry of a failed connection, on the real graph and read off the wire.
 *
 * 2026-10-02 16:43: ACL_CONNECTED, one attempt, `Could not open a transport`, the service stopped,
 * and the motorcycle stayed linked for another 11 s with nobody trying again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectE2eTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val linkDown = BleDisconnectedException("GATT connection failed")

    @Test
    fun `a link that would not open is opened again and the session reaches the cluster`() = runTest {
        val harness = SessionHarness(backgroundScope, reconnect = ReconnectPolicy.DEFAULT)
        harness.btu.boot()
        harness.aclPresence.record(AutoConnectEvaluator.ACTION_ACL_CONNECTED, SessionHarness.MAC)
        harness.transportFactory.failures += linkDown

        harness.sessionLauncher.start(SessionHarness.MAC, SessionOrigin.RECEIVER)
        runCurrent()
        assertEquals(SabSessionState.Connecting(ConnectionStep.WAITING_TO_RETRY), harness.sessions.state.value)
        assertEquals("nothing may reach the BTU from a link that never opened", 0, harness.btu.frames.size)

        advanceTimeBy(2_001)
        runCurrent()

        assertTrue(
            "got: ${harness.sessions.state.value}",
            harness.sessions.state.value is SabSessionState.Connected
        )
        assertEquals(listOf(SessionHarness.MAC, SessionHarness.MAC), harness.transportFactory.connectedAddresses)
        assertEquals(
            "the retried session asks for the screen exactly once",
            1,
            harness.btu.frames.count { it.commandId == CommandId.REQUEST_SAB_MODE.value }
        )
    }

    /** 2026-10-07 21:10:46: ACL_DISCONNECTED 75 ms after the failure, then a fresh ACL_CONNECTED. */
    @Test
    fun `the motorcycle leaving stops the retry and its return opens one session`() = runTest {
        val harness = SessionHarness(backgroundScope, reconnect = ReconnectPolicy.DEFAULT)
        harness.btu.boot()
        harness.aclPresence.record(AutoConnectEvaluator.ACTION_ACL_CONNECTED, SessionHarness.MAC)
        harness.transportFactory.failures += linkDown
        harness.sessionLauncher.start(SessionHarness.MAC, SessionOrigin.RECEIVER)
        runCurrent()

        harness.aclPresence.record(AutoConnectEvaluator.ACTION_ACL_DISCONNECTED, SessionHarness.MAC)
        runCurrent()
        assertTrue(harness.sessions.state.value is SabSessionState.Error)

        harness.aclPresence.record(AutoConnectEvaluator.ACTION_ACL_CONNECTED, SessionHarness.MAC)
        harness.sessionLauncher.start(SessionHarness.MAC, SessionOrigin.RECEIVER)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(harness.sessions.state.value is SabSessionState.Connected)
        assertEquals(
            "one failed attempt and one good one: the abandoned retry opened nothing",
            2,
            harness.transportFactory.connectedAddresses.size
        )
    }

    @Test
    fun `without a linked motorcycle a failure is final`() = runTest {
        val harness = SessionHarness(backgroundScope, reconnect = ReconnectPolicy.DEFAULT)
        harness.btu.boot()
        harness.transportFactory.failures += linkDown

        harness.sessionLauncher.start(SessionHarness.MAC, SessionOrigin.SCANNER)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(harness.sessions.state.value is SabSessionState.Error)
        assertEquals(1, harness.transportFactory.connectedAddresses.size)
    }
}
