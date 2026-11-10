package com.opensab.app.e2e

import com.opensab.app.ui.MainDispatcherRule
import com.opensab.app.ui.dashboard.DashboardUiIntent
import com.opensab.app.ui.scan.VehicleScanUiIntent
import com.opensab.cluster.CueSessionAnnouncer
import com.opensab.cluster.mirror.MirrorState
import com.opensab.domain.audio.Cue
import com.opensab.sab.protocol.projection.CommandId
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.ble.ScannedDevice
import com.opensab.sab.session.vehicle.ClusterDisplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import timber.log.Timber

/**
 * Sessions opened the only way production opens them: a START through [SessionHarness.sessionLauncher]
 * into the real `SabSessionController`, never a direct call into the pipeline. Connecting through a
 * path the app does not take once let the dashboard's handover button fail on every real session
 * while the whole suite stayed green.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerSessionE2eTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `the dashboard can ask for the cluster screen on a session the controller opened`() = runTest {
        val warnings = WarningTree()
        Timber.plant(warnings)
        try {
            val harness = SessionHarness(backgroundScope)
            harness.btu.boot()
            harness.sessionLauncher.start(SessionHarness.MAC, SessionOrigin.RECEIVER)
            runCurrent()
            assertTrue(
                "The controller has to connect before the dashboard can ask for anything",
                harness.sessions.state.value is SabSessionState.Connected
            )
            harness.handOverScreen()
            runCurrent()
            // The rider takes the screen back on the cluster, which is when the button matters.
            harness.btu.returnToHuMode()
            runCurrent()

            val dashboard = harness.dashboardViewModel()
            val screen = backgroundScope.launch { dashboard.uiState.collect { } }
            runCurrent()
            val requestsBefore = harness.btu.frames.count { it.commandId == CommandId.REQUEST_SAB_MODE.value }

            dashboard.sendIntent(DashboardUiIntent.RequestHandover)
            runCurrent()

            assertEquals(
                "The request for the screen must not be refused",
                emptyList<String>(),
                warnings.failures
            )
            assertEquals(
                "One REQUEST_SAB_MODE has to reach the BTU for the press",
                requestsBefore + 1,
                harness.btu.frames.count { it.commandId == CommandId.REQUEST_SAB_MODE.value }
            )
            assertEquals(
                MirrorState.ClusterOwnsScreen(handoverPending = true),
                harness.mirror.state.value
            )
            screen.cancel()
        } finally {
            Timber.uproot(warnings)
        }
    }

    /** A session the controller opens sounds its link cue, not only its unlink. */
    @Test
    fun `a session the controller opened sounds the link and the unlink once each`() = runTest {
        val harness = SessionHarness(backgroundScope)

        harness.connect().getOrThrow()
        runCurrent()
        harness.disconnect()
        runCurrent()

        assertEquals(listOf(Cue.LINK_UP, Cue.LINK_DOWN), harness.cuePlayer.played)
    }

    /**
     * 2026-10-03 13:16, session #9: the BTU linked again 170 ms after the key-off drop and was gone
     * 2.3 s after the session connected. The rider heard LINK_DOWN, LINK_UP and LINK_DOWN in six
     * seconds for one turn of the key.
     */
    @Test
    fun `a link the BTU drops within seconds is neither announced nor mourned`() = runTest {
        val harness = SessionHarness(backgroundScope, linkSettle = CueSessionAnnouncer.LINK_SETTLE)
        harness.connect().getOrThrow()
        advanceTimeBy(2_300)
        runCurrent()

        harness.btu.transport.disconnect()
        runCurrent()
        advanceTimeBy(CueSessionAnnouncer.HEADSET_WAIT.inWholeMilliseconds)
        runCurrent()

        assertEquals(SabSessionState.Disconnected, harness.sessions.state.value)
        assertEquals(emptyList<Cue>(), harness.cuePlayer.played)
    }

    @Test
    fun `a link that lasts is announced once it settles and so is its loss`() = runTest {
        val harness = SessionHarness(backgroundScope, linkSettle = CueSessionAnnouncer.LINK_SETTLE)
        harness.connect().getOrThrow()
        advanceTimeBy(CueSessionAnnouncer.LINK_SETTLE.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals(listOf(Cue.LINK_UP), harness.cuePlayer.played)

        harness.btu.transport.disconnect()
        runCurrent()

        assertEquals(listOf(Cue.LINK_UP, Cue.LINK_DOWN), harness.cuePlayer.played)
    }

    /** Owned by `KnownVehicleParticipant`: the scan screen must not save the motorcycle as well. */
    @Test
    fun `pairing from the scanner saves the motorcycle exactly once`() = runTest {
        val harness = SessionHarness(backgroundScope)
        harness.btu.boot()

        val scanViewModel = harness.scanViewModel()
        scanViewModel.sendIntent(
            VehicleScanUiIntent.ConnectToDevice(ScannedDevice("HONDA BTU 73FD", SessionHarness.MAC, -60))
        )
        runCurrent()
        // A motorcycle scanned for the first time asks for its cluster type before pairing starts.
        scanViewModel.sendIntent(VehicleScanUiIntent.AnswerClusterDisplay(ClusterDisplay.COLOR))
        runCurrent()
        assertTrue(
            "The scanner's request has to connect first",
            harness.sessions.state.value is SabSessionState.Connected
        )
        runCurrent()

        assertEquals(listOf(SessionHarness.MAC), harness.vehicles.saved)
    }

    /** What the dashboard logs when it cannot get the screen: the throwable carries the reason. */
    private class WarningTree : Timber.Tree() {
        val failures = mutableListOf<String>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (t != null) failures += "${message.lineSequence().first()}: ${t.message}"
        }
    }
}
