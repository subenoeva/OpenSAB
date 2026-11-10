package com.opensab.sab.session

import com.opensab.logging.Log
import com.opensab.logging.LogSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SabSessionTest {

    private val recordedLines = Collections.synchronizedList(mutableListOf<String>())
    private val sink = LogSink { _, _, message, args ->
        recordedLines += if (args.isEmpty()) message.orEmpty() else String.format(message.orEmpty(), *args)
    }

    @Before
    fun installSink() {
        Log.install(sink)
    }

    @After
    fun uninstallSink() {
        Log.uninstall(sink)
    }

    private fun session(
        id: Long = 1L,
        origin: SessionOrigin = SessionOrigin.RECEIVER,
        parent: kotlin.coroutines.CoroutineContext = Dispatchers.Default,
        stallThreshold: kotlin.time.Duration? = null,
        onCloseRequested: (SabSession, CloseReason) -> Unit = { _, _ -> },
    ) = SabSession(
        id = id,
        macAddress = "AA:BB:CC:DD:EE:FF",
        origin = origin,
        parent = parent,
        stallThreshold = stallThreshold,
        onCloseRequested = onCloseRequested,
    )

    /**
     * Two coroutines launched into [scope]; the first enters, then waits up to 500 ms for the second
     * to signal it entered too. A confined scope never lets the second in while the first waits, so
     * the max observed occupancy proves (or disproves) mutual exclusion.
     */
    private fun measureMaxInFlight(scope: CoroutineScope): Int = runBlocking {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val secondEntered = CountDownLatch(1)

        val first = scope.launch {
            val n = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { current -> maxOf(current, n) }
            secondEntered.await(500, TimeUnit.MILLISECONDS)
            inFlight.decrementAndGet()
        }
        val second = scope.launch {
            val n = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { current -> maxOf(current, n) }
            secondEntered.countDown()
            inFlight.decrementAndGet()
        }
        first.join()
        second.join()
        maxInFlight.get()
    }

    /**
     * Field-log greps anchor on this exact text; a session with no line here is invisible to
     * them.
     */
    @Test
    fun `a session logs its own opening`() {
        val session = session(id = 3L, origin = SessionOrigin.SCANNER)
        assertTrue(
            "expected an opened line, got: $recordedLines",
            recordedLines.any {
                it.contains("SabSession #3") && it.contains("opened for") &&
                    it.contains(session.macAddress) && it.contains("SCANNER")
            }
        )
        runBlocking { session.close(CloseReason.DISCONNECT) }
    }

    @Test
    fun `the scope runs one coroutine at a time on a multi-thread dispatcher`() {
        val session = session(parent = Dispatchers.Default)
        val maxInFlight = measureMaxInFlight(session.scope)
        assertEquals(1, maxInFlight)
        runBlocking { session.close(CloseReason.DISCONNECT) }
    }

    @Test
    fun `a plain Default scope does run them together`() {
        val plain = CoroutineScope(Dispatchers.Default)
        val maxInFlight = measureMaxInFlight(plain)
        assertEquals(2, maxInFlight)
        plain.cancel()
    }

    @Test
    fun `close cancels every coroutine launched into the scope`() = runBlocking {
        val session = session()
        var completed = false
        val job = session.scope.launch {
            delay(10.seconds)
            completed = true
        }
        session.close(CloseReason.DISCONNECT)
        assertTrue(job.isCancelled)
        assertFalse(completed)
    }

    @Test
    fun `close runs the actions last registered first`() = runBlocking {
        val order = mutableListOf<String>()
        val session = session()
        session.onClose("a") { order += "a" }
        session.onClose("b") { order += "b" }
        session.close(CloseReason.DISCONNECT)
        assertEquals(listOf("b", "a"), order)
    }

    @Test
    fun `a failing close action is logged and the others still run`() = runBlocking {
        val order = mutableListOf<String>()
        val session = session(id = 7L)
        session.onClose("bad") { throw RuntimeException("boom") }
        session.onClose("good") { order += "good" }
        session.close(CloseReason.DISCONNECT)

        assertEquals(listOf("good"), order)
        assertTrue(
            "expected a warning about the failing action, got: $recordedLines",
            recordedLines.any { it.contains("SabSession #7") && it.contains("bad") && it.contains("failed") }
        )
    }

    @Test
    fun `a suspending close action completes although the scope is being cancelled`() = runBlocking {
        val session = session()
        var finished = false
        session.onClose("slow") {
            delay(50.milliseconds)
            finished = true
        }
        session.close(CloseReason.DISCONNECT)
        assertTrue(finished)
    }

    @Test
    fun `closing twice runs the actions once`() = runBlocking {
        var count = 0
        val session = session()
        session.onClose("x") { count++ }
        session.close(CloseReason.DISCONNECT)
        session.close(CloseReason.DISCONNECT)
        assertEquals(1, count)
    }

    @Test
    fun `closed in is logged even when the caller is cancelled while the scope drains`() = runBlocking {
        val session = session(id = 11L)
        // A child that ignores cancellation keeps the scope's job alive after cancel(), so close()
        // is suspended in the join when its caller goes away.
        session.scope.launch { withContext(NonCancellable) { delay(300.milliseconds) } }
        val closer = launch(Dispatchers.Default) { session.close(CloseReason.DISCONNECT) }
        delay(100.milliseconds)
        closer.cancelAndJoin()

        assertTrue(
            "expected a closed-in line, got: $recordedLines",
            recordedLines.any { it.contains("SabSession #11 closed in") }
        )
    }

    @Test
    fun `requestClose goes to the owner and closes nothing by itself`() = runBlocking {
        val requests = mutableListOf<Pair<SabSession, CloseReason>>()
        val session = session(onCloseRequested = { s, reason -> requests += s to reason })
        var ran = false
        session.onClose("x") { ran = true }

        session.requestClose(CloseReason.LINK_LOST)

        assertEquals(listOf(session to CloseReason.LINK_LOST), requests)
        assertFalse(ran)
        assertFalse(session.isClosed)
    }

    @Test
    fun `a stalled session thread is logged`() {
        val session = session(id = 9L, parent = Dispatchers.Default, stallThreshold = 250.milliseconds)
        // Occupies the confined slot for 1.4 s straight, so the 1 s heartbeat's dispatch queues
        // behind it and wakes late.
        session.scope.launch { Thread.sleep(1_400) }
        runBlocking { delay(1_600.milliseconds) }

        assertTrue(
            "expected a stalled-thread warning, got: $recordedLines",
            recordedLines.any { it.contains("SabSession #9") && it.contains("thread stalled") }
        )
        runBlocking { session.close(CloseReason.DISCONNECT) }
    }

    @Test
    fun `a test dispatcher parent keeps the session deterministic`() = runTest {
        val session = session(parent = coroutineContext)
        var fired = false
        session.scope.launch {
            delay(1.seconds)
            fired = true
        }
        assertFalse(fired)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(fired)
        session.close(CloseReason.DISCONNECT)
    }
}
