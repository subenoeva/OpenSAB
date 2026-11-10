package com.opensab.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class LogTest {

    private data class Call(val priority: Int, val t: Throwable?, val message: String?, val args: List<Any?>)

    private class RecordingSink : LogSink {
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        override fun log(priority: Int, t: Throwable?, message: String?, args: Array<out Any?>) {
            calls += Call(priority, t, message, args.toList())
        }
    }

    private val sink = RecordingSink()

    @After
    fun uninstall() = Log.uninstall(sink)

    @Test
    fun linesBeforeInstallAreDroppedNotBuffered() {
        Log.w("nobody is listening")
        Log.install(sink)
        assertEquals(emptyList<Call>(), sink.calls)
    }

    @Test
    fun verboseReachesTheSinkWithItsOwnPriority() {
        Log.install(sink)
        Log.v("v %d", 1)
        assertEquals(listOf(Call(Log.VERBOSE, null, "v %d", listOf(1))), sink.calls)
        assertEquals(2, Log.VERBOSE)
    }

    @Test
    fun eachLevelReachesTheSinkWithItsPriority() {
        Log.install(sink)
        Log.d("d")
        Log.i("i")
        Log.w("w")
        Log.e("e")
        assertEquals(listOf(3, 4, 5, 6), sink.calls.map { it.priority })
    }

    @Test
    fun formatArgumentsReachTheSinkUnformatted() {
        Log.install(sink)
        Log.i("Series %s resolved to %s (%d catalog candidate(s))", "CB650R", "RH02", 1)
        assertEquals(
            Call(Log.INFO, null, "Series %s resolved to %s (%d catalog candidate(s))", listOf("CB650R", "RH02", 1)),
            sink.calls.single()
        )
    }

    @Test
    fun aThrowableReachesTheSinkBesideItsMessage() {
        val error = IllegalStateException("boom")
        Log.install(sink)
        Log.e(error, "Could not archive the corrupt store %s", "vehicles")
        assertEquals(
            Call(Log.ERROR, error, "Could not archive the corrupt store %s", listOf("vehicles")),
            sink.calls.single()
        )
    }

    @Test
    fun aNullMessageReachesTheSinkAsNull() {
        val message: String? = null
        Log.install(sink)
        Log.w(message)
        assertEquals(Call(Log.WARN, null, null, emptyList()), sink.calls.single())
    }

    @Test
    fun aSecondInstallFailsBecauseTheApplicationOwnsTheSink() {
        Log.install(sink)
        val failure = runCatching { Log.install(RecordingSink()) }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $failure", failure is IllegalStateException)
    }

    @Test
    fun uninstallingAnotherSinkLeavesTheInstalledOneInPlace() {
        Log.install(sink)
        Log.uninstall(RecordingSink())
        Log.i("still here")
        assertEquals(1, sink.calls.size)
    }

    @Test
    fun callsFromManyThreadsAllReachTheSink() {
        Log.install(sink)
        val start = CountDownLatch(1)
        val workers = (1..8).map { n ->
            thread { start.await(); repeat(250) { Log.d("worker %d line %d", n, it) } }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(2000, sink.calls.size)
    }
}
