package com.opensab.app.e2e

import com.opensab.app.ui.MainDispatcherRule
import com.opensab.app.ui.dashboard.DashboardUiIntent
import com.opensab.app.ui.scan.VehicleScanUiIntent
import com.opensab.cluster.mirror.MirrorState
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.projection.BodyType
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.ble.ScannedDevice
import com.opensab.sab.session.vehicle.ClusterDisplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The session outliving the screen that opened it, measured on the cable.
 *
 * `VehicleScanViewModelTest` already asserts that the scope handed to the pipeline stays active
 * after the scan screen is cleared, but it does so against a stub orchestrator that starts nothing.
 * A live scope with the CCCDs torn down is exactly what happened on 2026-08-27: the log said
 * `OpenSAB connected successfully` and 800 ms later `Could not disable notifications for
 * 6c060578-...`, and not one frame ever reached the cluster. So this asks the other question —
 * after the screen is gone, does a press still paint?
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleE2eTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val device = ScannedDevice("HONDA BTU 73FD", SessionHarness.MAC, -60)

    @Test
    fun `a press still paints after the scan screen is cleared`() = runTest {
        val harness = SessionHarness(backgroundScope)
        harness.btu.boot()
        val scan = harness.scanViewModel()

        scan.sendIntent(VehicleScanUiIntent.ConnectToDevice(device))
        runCurrent()
        // A motorcycle scanned for the first time asks for its cluster type before pairing starts.
        scan.sendIntent(VehicleScanUiIntent.AnswerClusterDisplay(ClusterDisplay.COLOR))
        runCurrent()
        assertTrue(
            "The rider has to be connected before the screen goes away",
            harness.sessions.state.value is SabSessionState.Connected
        )
        harness.handOverScreen()
        runCurrent()
        val paintedBeforeTheClear = harness.btu.baseFrames().size

        // The rider lands on the dashboard and the scan screen is torn down with its scope.
        harness.viewModelStore.clear()
        runCurrent()

        harness.btu.press(FunctionId.NEXT)
        runCurrent()

        assertTrue(
            "A press after the screen is gone still has to reach the cluster",
            harness.btu.baseFrames().size > paintedBeforeTheClear
        )
    }

    @Test
    fun `the dashboard built after the clear sees the live session`() = runTest {
        val harness = SessionHarness(backgroundScope)
        harness.btu.boot()
        val scan = harness.scanViewModel()
        scan.sendIntent(VehicleScanUiIntent.ConnectToDevice(device))
        runCurrent()
        scan.sendIntent(VehicleScanUiIntent.AnswerClusterDisplay(ClusterDisplay.COLOR))
        runCurrent()
        harness.handOverScreen()
        runCurrent()
        harness.viewModelStore.clear()
        runCurrent()

        val dashboard = harness.dashboardViewModel()
        // The mirror collector is `whileSubscribed()` on purpose: it only paints, so it idles while
        // the screen is not observed. Without a subscriber it never starts and the state stays Idle.
        val screen = backgroundScope.launch { dashboard.uiState.collect { } }
        runCurrent()

        val state = dashboard.uiState.value
        screen.cancel()
        assertTrue(
            "The dashboard is a second window on the session, not a second session",
            state.sessionState is SabSessionState.Connected
        )
        val mirror = state.mirrorState
        assertTrue("And it mirrors the page the cluster is showing: $mirror", mirror is MirrorState.Projecting)
        assertEquals(
            "The trail is the stack, bottom to top",
            listOf("RootMenu"),
            (mirror as MirrorState.Projecting).trail
        )
    }

    /**
     * A `@Singleton` graph means the second connection reuses every object of the first, so anything
     * a teardown forgets is inherited. `DashboardMode` was the one that mattered: a retained
     * SAB_MODE made `SabModeWatchdog` never fire against a cluster that had granted nothing.
     */
    @Test
    fun `disconnecting tears everything down and a second connection starts clean`() = runTest {
        val harness = SessionHarness(backgroundScope)
        harness.btu.boot()
        val scan = harness.scanViewModel()
        scan.sendIntent(VehicleScanUiIntent.ConnectToDevice(device))
        runCurrent()
        scan.sendIntent(VehicleScanUiIntent.AnswerClusterDisplay(ClusterDisplay.COLOR))
        runCurrent()
        harness.handOverScreen()
        runCurrent()
        val dashboard = harness.dashboardViewModel()

        dashboard.sendIntent(DashboardUiIntent.Disconnect)
        runCurrent()

        assertEquals(
            "No screen ownership without a live session",
            DashboardMode.HU_MODE,
            harness.inputDispatcher.currentMode.value
        )
        assertEquals(
            "The engine drops its pages, so nothing of the last ride is left mounted",
            emptyList<String>(),
            harness.engine.stack.snapshot().map { it.name }
        )

        val again = harness.connect()
        assertTrue("A second connection has to run the whole pipeline again", again.isSuccess)
        harness.handOverScreen()
        runCurrent()

        val frame = harness.btu.baseFrames().last()
        assertEquals(
            "And it opens on the carousel, not on whatever was mounted last time",
            BodyType.MENU.value,
            frame.bodyType
        )
    }
}
