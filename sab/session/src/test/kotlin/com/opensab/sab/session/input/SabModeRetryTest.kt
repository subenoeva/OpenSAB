package com.opensab.sab.session.input

import com.opensab.sab.protocol.input.DashboardMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SabModeRetryTest {

    /** The BTU as the retry sees it: the mode as state, and every mode notification as an event. */
    private class Cluster {
        val mode = MutableStateFlow(DashboardMode.HU_MODE)
        val notifications = MutableSharedFlow<DashboardMode>(extraBufferCapacity = 64)
        var requests = 0
        var assumed = 0

        fun notify(mode: DashboardMode) {
            this.mode.value = mode
            notifications.tryEmit(mode)
        }
    }

    private suspend fun TestScope.open(
        cluster: Cluster,
        fallbacks: SabModeFallbacks = SabModeFallbacks(),
        onRequest: () -> Unit = {}
    ) = SabModeRetry(timeout = 5.seconds).requestUntilGranted(
        mode = cluster.mode.asStateFlow(),
        modeNotifications = cluster.notifications,
        scope = backgroundScope,
        fallbacks = fallbacks,
        request = {
            cluster.requests++
            onRequest()
        },
        assumeGranted = { cluster.assumed++ }
    )

    // --- Both fallbacks off: what the 5 s retry has always done -------------------------------

    @Test
    fun shouldWriteTheOpeningRequestBeforeReturning() = runTest {
        val cluster = Cluster()

        open(cluster)

        assertEquals("The opening request is written inline, not scheduled", 1, cluster.requests)
    }

    @Test
    fun shouldRequestAgainExactlyOnceWhenNo0x09ArrivesWithinTheTimeout() = runTest {
        val cluster = Cluster()

        open(cluster)

        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals("Must not request again before the timeout elapses", 1, cluster.requests)

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals("Must request again once the timeout elapsed", 2, cluster.requests)

        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals("The second request is the last one: no loop", 2, cluster.requests)
        assertEquals("Nothing is assumed unless the rider opted in", 0, cluster.assumed)
    }

    @Test
    fun shouldNotRequestAgainWhenTheClusterGrantsSabModeBeforeTheTimeout() = runTest {
        val cluster = Cluster()

        open(cluster)

        advanceTimeBy(1.seconds)
        cluster.notify(DashboardMode.SAB_MODE)
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals("A granted handover needs no second request", 1, cluster.requests)
    }

    @Test
    fun shouldNotRequestAgainWhenTheClusterGrantedAndTheRiderTookItBackBeforeTheTimeout() = runTest {
        val cluster = Cluster()

        open(cluster)

        advanceTimeBy(1.seconds)
        cluster.notify(DashboardMode.SAB_MODE)
        runCurrent()
        cluster.notify(DashboardMode.HU_MODE)
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals("Going back to HU_MODE is the rider's call, never re-requested", 1, cluster.requests)
    }

    @Test
    fun shouldIgnoreHuModeNotificationsWhenTheReRequestIsOff() = runTest {
        val cluster = Cluster()

        open(cluster)
        repeat(3) {
            advanceTimeBy(1.seconds)
            cluster.notify(DashboardMode.HU_MODE)
        }
        advanceTimeBy(60.seconds)
        runCurrent()

        assertEquals("Only the opening request and the one at the timeout", 2, cluster.requests)
    }

    // --- Re-request on HU_MODE ------------------------------------------------------------------

    @Test
    fun shouldRequestAgainOnAHuModeNotificationBeforeTheFirstGrant() = runTest {
        val cluster = Cluster()

        open(cluster, SabModeFallbacks(rerequestOnHuMode = true))
        advanceTimeBy(100.milliseconds)
        cluster.notify(DashboardMode.HU_MODE)
        runCurrent()

        assertEquals("The HU_MODE notification asks again, without waiting for the timeout", 2, cluster.requests)
    }

    /**
     * On the bike the HU_MODE lands 3 to 37 ms after the 0x0A is written and before its Receipt,
     * so it arrives while the opening request is still suspended.
     */
    @Test
    fun shouldNotMissAHuModeNotificationThatArrivesWhileTheOpeningRequestIsInFlight() = runTest {
        val cluster = Cluster()
        var first = true

        open(cluster, SabModeFallbacks(rerequestOnHuMode = true)) {
            if (first) {
                first = false
                cluster.notify(DashboardMode.HU_MODE)
            }
        }
        runCurrent()

        assertEquals(2, cluster.requests)
    }

    @Test
    fun shouldNotRequestOnHuModeOnceTheClusterHasGranted() = runTest {
        val cluster = Cluster()

        open(cluster, SabModeFallbacks(rerequestOnHuMode = true))
        advanceTimeBy(1.seconds)
        cluster.notify(DashboardMode.SAB_MODE)
        runCurrent()
        repeat(3) {
            advanceTimeBy(1.seconds)
            cluster.notify(DashboardMode.HU_MODE)
            runCurrent()
        }
        advanceTimeBy(60.seconds)
        runCurrent()

        assertEquals("After the first grant HU_MODE is the rider's call", 1, cluster.requests)
    }

    @Test
    fun shouldStopListeningToHuModeAfterTheTimeout() = runTest {
        val cluster = Cluster()

        open(cluster, SabModeFallbacks(rerequestOnHuMode = true))
        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals("Opening request plus the one at the timeout", 2, cluster.requests)

        repeat(5) {
            advanceTimeBy(2.seconds)
            cluster.notify(DashboardMode.HU_MODE)
            runCurrent()
        }

        assertEquals("The window closed with the timeout", 2, cluster.requests)
    }

    /**
     * A BTU that answers every 0x0A with a HU_MODE would otherwise be asked as fast as the link
     * can carry the round trip.
     */
    @Test
    fun shouldSpaceReRequestsSoABtuThatEchoesHuModeCannotCauseAWriteStorm() = runTest {
        val cluster = Cluster()

        open(cluster, SabModeFallbacks(rerequestOnHuMode = true)) { cluster.notify(DashboardMode.HU_MODE) }
        runCurrent()
        assertEquals("The first HU_MODE is answered at once", 2, cluster.requests)

        advanceTimeBy(900.milliseconds)
        runCurrent()
        assertEquals("The next one waits for the spacing", 2, cluster.requests)

        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertEquals(3, cluster.requests)

        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(
            "Opening, five spaced re-requests inside the 5 s window, and the one at the timeout",
            7,
            cluster.requests
        )
    }

    // --- Projecting without the grant -----------------------------------------------------------

    @Test
    fun shouldAssumeTheGrantAfterTheTimeoutRequestWhenOptedIn() = runTest {
        val cluster = Cluster()
        val order = mutableListOf<String>()

        SabModeRetry(timeout = 5.seconds).requestUntilGranted(
            mode = cluster.mode.asStateFlow(),
            modeNotifications = cluster.notifications,
            scope = backgroundScope,
            fallbacks = SabModeFallbacks(projectWithoutGrant = true),
            request = { order += "request" },
            assumeGranted = { order += "assume" }
        )
        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals(listOf("request"), order)

        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals("Ask once more first, then project as if granted", listOf("request", "request", "assume"), order)
    }

    @Test
    fun shouldNotAssumeTheGrantWhenTheClusterGrantedInTime() = runTest {
        val cluster = Cluster()

        open(cluster, SabModeFallbacks(projectWithoutGrant = true))
        advanceTimeBy(1.seconds)
        cluster.notify(DashboardMode.SAB_MODE)
        advanceTimeBy(60.seconds)
        runCurrent()

        assertEquals(0, cluster.assumed)
    }
}
