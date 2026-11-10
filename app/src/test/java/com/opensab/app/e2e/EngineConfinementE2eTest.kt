package com.opensab.app.e2e

import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.protocol.projection.Receipt
import com.opensab.sab.session.projection.ScreenProjector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val PRESS_COUNT = 50

/**
 * A handlebar press and a source-driven redraw both mutate `BaseStateManager` from `scope.launch`
 * collectors; on a multi-thread dispatcher with nothing else shared, they race.
 */
class EngineConfinementE2eTest {

    @Test
    fun `a press and a redraw never run the engine at the same time`() = runBlocking {
        val barrier = BarrierScreenProjector()
        // Owned by this test and cancelled below: a live session left on Default keeps logging
        // into whichever test class the JVM runs next.
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val harness = SessionHarness(sessionScope, projectorWrapper = barrier::wrap)
        try {
            withTimeout(20.seconds) {
                harness.connect().getOrThrow()
                harness.handOverScreen()
                barrier.awaitFirstFrame()
                barrier.arm()

                val go = CountDownLatch(1)
                val pressJob = launch(Dispatchers.Default) {
                    go.await()
                    repeat(PRESS_COUNT) { harness.btu.press(FunctionId.NEXT) }
                }
                val redrawJob = launch(Dispatchers.Default) {
                    go.await()
                    // A 1 ms stagger spreads the 50 emissions across the whole press storm instead of
                    // collapsing them into one StateFlow transition before the first press even lands.
                    repeat(PRESS_COUNT) {
                        harness.media.emitPlaying(it)
                        kotlinx.coroutines.delay(1.milliseconds)
                    }
                }
                go.countDown()
                joinAll(pressJob, redrawJob)
                // The engine's own collectors keep draining queued invalidations after the stimulus
                // loops return; give a straggler its chance to land before reading the peak.
                kotlinx.coroutines.delay(300.milliseconds)
            }
        } finally {
            sessionScope.cancel()
        }

        assertEquals("two engine mutations overlapped", 1, barrier.maxInFlight)
    }
}

/**
 * Counts how many `updateBaseScreen` calls are in flight at once. Once [arm]ed, the next call
 * waits up to 2 s for a second one to arrive concurrently — proof the two collectors are (or are
 * not) serialized, since with a confined scope the second can never be dispatched while the first
 * is still running.
 */
class BarrierScreenProjector {
    private val inFlight = AtomicInteger(0)
    private val lock = Any()

    @Volatile
    var maxInFlight: Int = 0
        private set

    private val firstFrame = CompletableDeferred<Unit>()

    @Volatile
    private var armed = false
    private val waitingForSecond = AtomicBoolean(false)
    private val secondArrived = CountDownLatch(1)
    val callsAfterArm = AtomicInteger(0)

    suspend fun awaitFirstFrame() = firstFrame.await()

    fun arm() {
        armed = true
    }

    fun wrap(delegate: ScreenProjector): ScreenProjector = object : ScreenProjector by delegate {
        override suspend fun updateBaseScreen(frame: ProjectionFrame): Result<Receipt> {
            val n = inFlight.incrementAndGet()
            synchronized(lock) { if (n > maxInFlight) maxInFlight = n }
            if (!firstFrame.isCompleted) firstFrame.complete(Unit)
            try {
                if (armed) {
                    callsAfterArm.incrementAndGet()
                    if (waitingForSecond.compareAndSet(false, true)) {
                        secondArrived.await(2, TimeUnit.SECONDS)
                    } else {
                        secondArrived.countDown()
                    }
                }
                return delegate.updateBaseScreen(frame)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }
}
