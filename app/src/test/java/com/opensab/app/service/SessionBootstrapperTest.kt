package com.opensab.app.service

import com.opensab.sab.session.SessionOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SessionBootstrapperTest {

    private val mac = "02:00:00:00:00:AB"

    private class RecordingLauncher : SessionLauncher {
        val calls = mutableListOf<String>()
        override fun start(macAddress: String, origin: SessionOrigin) {
            calls += "start $macAddress $origin"
        }
        override fun stop(origin: SessionOrigin) {
            calls += "stop $origin"
        }
    }

    private val launcher = RecordingLauncher()
    private var activityLaunches = 0

    private fun bootstrapper(
        scope: CoroutineScope,
        overlay: Boolean = true,
        launchSucceeds: Boolean = true
    ) = SessionBootstrapper(
        launcher = launcher,
        overlayGranted = { overlay },
        launchActivity = { activityLaunches++; launchSucceeds },
        scope = scope,
        claimTimeout = 5.seconds
    )

    @Test
    fun withoutTheOverlayPermissionTheReceiverStartsTheSessionDirectly() = runTest {
        val b = bootstrapper(backgroundScope, overlay = false)

        b.connect(mac)

        assertEquals(0, activityLaunches)
        assertEquals(listOf("start $mac RECEIVER"), launcher.calls)
    }

    @Test
    fun withTheOverlayTheStartWaitsForTheVisibleActivity() = runTest {
        val b = bootstrapper(backgroundScope)

        b.connect(mac)

        assertEquals(1, activityLaunches)
        assertEquals(emptyList<String>(), launcher.calls)
        assertTrue(b.startClaimed())
        assertEquals(listOf("start $mac RECEIVER"), launcher.calls)
    }

    @Test
    fun aLaunchTheSystemRefusesFallsBackToTheDirectStart() = runTest {
        val b = bootstrapper(backgroundScope, launchSucceeds = false)

        b.connect(mac)

        assertEquals(listOf("start $mac RECEIVER"), launcher.calls)
        assertFalse(b.startClaimed())
    }

    /** Android 14 blocks a background launch in silence: no exception, the activity never comes. */
    @Test
    fun anActivityThatNeverArrivesDoesNotCostTheSession() = runTest {
        val b = bootstrapper(backgroundScope)
        b.connect(mac)

        advanceAndRun(4.seconds)
        assertEquals(emptyList<String>(), launcher.calls)
        advanceAndRun(1.seconds + 1.milliseconds)

        assertEquals(listOf("start $mac RECEIVER"), launcher.calls)
    }

    @Test
    fun anActivityArrivingAfterTheFallbackDoesNotStartASecondTime() = runTest {
        val b = bootstrapper(backgroundScope)
        b.connect(mac)
        advanceAndRun(6.seconds)

        assertFalse(b.startClaimed())
        assertEquals(listOf("start $mac RECEIVER"), launcher.calls)
    }

    @Test
    fun aClaimedStartDisarmsTheFallback() = runTest {
        val b = bootstrapper(backgroundScope)
        b.connect(mac)
        b.startClaimed()

        advanceAndRun(10.seconds)

        assertEquals(listOf("start $mac RECEIVER"), launcher.calls)
    }

    @Test
    fun aDisconnectBeforeTheActivityArrivesCancelsThePendingStart() = runTest {
        val b = bootstrapper(backgroundScope)
        b.connect(mac)

        b.disconnect()
        advanceAndRun(10.seconds)

        assertFalse(b.startClaimed())
        assertEquals(listOf("stop RECEIVER"), launcher.calls)
    }

    private fun TestScope.advanceAndRun(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }
}
