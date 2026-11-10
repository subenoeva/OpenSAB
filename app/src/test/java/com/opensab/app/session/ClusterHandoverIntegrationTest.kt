package com.opensab.app.session

import com.opensab.cluster.mirror.MirrorState
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.sab.session.input.SabModeWatchdog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `DefaultClusterHandoverCoordinator` against the real graph.
 *
 * Kept apart from the pipeline test on purpose: the coordinator and the pipeline fail for different
 * reasons, and a red here should say which.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClusterHandoverIntegrationTest {

    /**
     * The flag goes up as the intent leaves, so the button is disabled from the moment the rider
     * presses it rather than from whenever the cluster answers.
     */
    @Test
    fun `requestClusterHandover marks the request pending and asks the cluster`() = runTest {
        val mirror = ProjectionMirror()
        val rig = sessionIntegrationRig(projectionMirror = mirror)
        val transport = createPrimedTransport(seriesCode = "052B")
        rig.connect(transport).getOrThrow()
        // The engine's mode collector publishes ClusterOwnsScreen on its first emission;
        // let it land before asking, or it would clear the flag the request just raised.
        runCurrent()
        mirror.publishClusterOwnsScreen()

        val result = rig.requestClusterHandover()

        assertTrue(result.isSuccess)
        assertEquals(
            MirrorState.ClusterOwnsScreen(handoverPending = true),
            mirror.state.value
        )
    }

    /**
     * The Receipt of the 0x0A acknowledges the command, never the handover, so no state may reach
     * `Projecting` until the 0x09 notification arrives.
     */
    @Test
    fun `the receipt of the request never grants the screen`() = runTest {
        val mirror = ProjectionMirror()
        val rig = sessionIntegrationRig(projectionMirror = mirror)
        val transport = createPrimedTransport(seriesCode = "052B")
        rig.connect(transport).getOrThrow()
        // The engine's mode collector publishes ClusterOwnsScreen on its first emission;
        // let it land before asking, or it would clear the flag the request just raised.
        runCurrent()
        mirror.publishClusterOwnsScreen()

        rig.requestClusterHandover()

        assertFalse(
            "Only a 0x09 FunctionNotification grants the screen",
            mirror.state.value is MirrorState.Projecting
        )
    }

    @Test
    fun `a cluster that never grants the screen puts the flag back down`() = runTest {
        val mirror = ProjectionMirror()
        val rig = sessionIntegrationRig(
            projectionMirror = mirror,
            sabModeWatchdog = SabModeWatchdog(timeout = 10.seconds)
        )
        val transport = createPrimedTransport(seriesCode = "052B")
        rig.connect(transport).getOrThrow()
        // The engine's mode collector publishes ClusterOwnsScreen on its first emission;
        // let it land before asking, or it would clear the flag the request just raised.
        runCurrent()
        mirror.publishClusterOwnsScreen()
        rig.requestClusterHandover()
        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = true), mirror.state.value)

        advanceTimeBy(10_001.milliseconds)
        runCurrent()

        assertEquals(
            "Ten seconds of silence is a refusal, and the button has to come back",
            MirrorState.ClusterOwnsScreen(handoverPending = false, grantOverdue = true),
            mirror.state.value
        )
    }

    /**
     * The two callers of `publishClusterOwnsScreen` were saying different things: the engine's mode
     * collector says where the screen is, the watchdog says the cluster refuses it. Only the second
     * is a verdict, so a re-delivery of `HU_MODE` — the one every `BaseStateManager.start` gets on
     * re-subscribing to `currentMode` — must leave the button disabled and the request alive.
     */
    @Test
    fun `only the watchdog verdict puts the flag back down, not a re-delivery of HU_MODE`() = runTest {
        val mirror = ProjectionMirror()
        val rig = sessionIntegrationRig(
            projectionMirror = mirror,
            sabModeWatchdog = SabModeWatchdog(timeout = 10.seconds)
        )
        val transport = createPrimedTransport(seriesCode = "052B")
        rig.connect(transport).getOrThrow()
        runCurrent()
        mirror.publishClusterOwnsScreen()
        rig.requestClusterHandover()

        // The cluster stays in HU_MODE and the mode is delivered again, which is not an answer.
        mirror.publishClusterOwnsScreen()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(
            "A re-delivery of the mode is not a verdict: the request is still in flight",
            MirrorState.ClusterOwnsScreen(handoverPending = true),
            mirror.state.value
        )

        advanceTimeBy(5_001.milliseconds)
        runCurrent()

        assertEquals(
            "The watchdog firing without a 0x09 is the verdict, and the button has to come back",
            MirrorState.ClusterOwnsScreen(handoverPending = false, grantOverdue = true),
            mirror.state.value
        )
    }

    /**
     * The request the session makes on its own at open gets the same verdict as the rider's: with
     * no 0x09 when the wait runs out, the dashboard is told, without anybody pressing the button.
     */
    @Test
    fun `the session-opening request running out raises the overdue grant on its own`() = runTest {
        val mirror = ProjectionMirror()
        val rig = sessionIntegrationRig(
            projectionMirror = mirror,
            sabModeWatchdog = SabModeWatchdog(timeout = 10.seconds)
        )
        rig.connect(createPrimedTransport(seriesCode = "052B")).getOrThrow()
        runCurrent()
        assertEquals(MirrorState.ClusterOwnsScreen(), mirror.state.value)

        advanceTimeBy(10_001.milliseconds)
        runCurrent()

        assertEquals(MirrorState.ClusterOwnsScreen(grantOverdue = true), mirror.state.value)
    }

    @Test
    fun `requestClusterHandover fails without a transport and leaves the mirror alone`() = runTest {
        val mirror = ProjectionMirror()
        val rig = sessionIntegrationRig(projectionMirror = mirror)
        mirror.publishClusterOwnsScreen()

        val result = rig.requestClusterHandover()

        assertTrue(result.isFailure)
        assertEquals(
            MirrorState.ClusterOwnsScreen(handoverPending = false),
            mirror.state.value
        )
    }
}
