package com.opensab.sab.session.input

import com.opensab.sab.protocol.input.DashboardMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SabModeWatchdogTest {

    @Test
    fun shouldReportWhenModeStaysInHuModeAfterTheTimeout() = runTest {
        val mode = MutableStateFlow(DashboardMode.HU_MODE)
        var reports = 0

        val job = SabModeWatchdog(timeout = 10.seconds)
            .start(mode.asStateFlow(), backgroundScope) { reports++ }

        advanceTimeBy(9.seconds)
        runCurrent()
        assertEquals("Must not report before the timeout elapses", 0, reports)

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals("Must report exactly once once the timeout elapsed", 1, reports)

        job.cancel()
    }

    @Test
    fun shouldStaySilentWhenClusterGrantsSabModeBeforeTheTimeout() = runTest {
        val mode = MutableStateFlow(DashboardMode.HU_MODE)
        var reports = 0

        val job = SabModeWatchdog(timeout = 10.seconds)
            .start(mode.asStateFlow(), backgroundScope) { reports++ }

        advanceTimeBy(3.seconds)
        mode.value = DashboardMode.SAB_MODE
        advanceTimeBy(20.seconds)
        runCurrent()

        assertEquals("A granted handover must silence the watchdog", 0, reports)

        job.cancel()
    }

    /** The question is whether the cluster granted, not where the mode happens to sit at the deadline. */
    @Test
    fun shouldStaySilentWhenTheRiderGoesBackToHuModeAfterAGrant() = runTest {
        val mode = MutableStateFlow(DashboardMode.HU_MODE)
        var reports = 0

        val job = SabModeWatchdog(timeout = 10.seconds)
            .start(mode.asStateFlow(), backgroundScope) { reports++ }

        advanceTimeBy(1.seconds)
        mode.value = DashboardMode.SAB_MODE
        advanceTimeBy(1.seconds)
        mode.value = DashboardMode.HU_MODE
        advanceTimeBy(20.seconds)
        runCurrent()

        assertEquals("A grant seen during the wait is a grant, whatever the mode is afterwards", 0, reports)

        job.cancel()
    }

    /** Read at each start: a change applies to the next request, never to a wait already running. */
    @Test
    fun shouldReadTheTimeoutAtEachStart() = runTest {
        val mode = MutableStateFlow(DashboardMode.HU_MODE)
        var chosen = 10.seconds
        var reads = 0
        var reports = 0
        val watchdog = SabModeWatchdog(timeout = { reads++; chosen })

        val first = watchdog.start(mode.asStateFlow(), backgroundScope) { reports++ }
        runCurrent()
        chosen = 20.seconds
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals("A wait already running keeps the timeout it started with", 1, reports)

        val second = watchdog.start(mode.asStateFlow(), backgroundScope) { reports++ }
        advanceTimeBy(19.seconds)
        runCurrent()
        assertEquals("The next request waits the new timeout", 1, reports)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(2, reports)
        assertEquals(2, reads)

        first.cancel()
        second.cancel()
    }
}
