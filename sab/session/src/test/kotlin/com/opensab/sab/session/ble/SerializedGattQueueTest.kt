package com.opensab.sab.session.ble

import com.opensab.sab.protocol.ble.SabGattUuids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SerializedGattQueueTest {

    @Test
    fun concurrentOperationsShouldExecuteInStrictSequenceWithoutInterleaving() = runBlocking {
        val queue = SerializedGattQueue(timeout = 1.seconds)
        val activeOperations = AtomicInteger(0)
        val executionOrder = mutableListOf<Int>()

        val jobs = (1..10).map { id ->
            async(Dispatchers.Default) {
                queue.execute {
                    val current = activeOperations.incrementAndGet()
                    assertEquals("Only one operation must be active at any given moment", 1, current)
                    delay(20.milliseconds) // simulate BLE round-trip latency
                    synchronized(executionOrder) {
                        executionOrder.add(id)
                    }
                    activeOperations.decrementAndGet()
                    id * 2
                }
            }
        }

        val results = jobs.awaitAll()
        assertEquals(10, results.size)
        assertEquals(0, activeOperations.get())
        assertEquals(10, executionOrder.size)
    }

    @Test
    fun timedOutOperationShouldThrowBleTimeoutExceptionAndUnblockSubsequentOnes() = runBlocking {
        val queue = SerializedGattQueue(timeout = 100.milliseconds)

        val failedResult = runCatching {
            queue.execute {
                delay(300.milliseconds) // Exceeds 100ms timeout
                "should_timeout"
            }
        }

        assertTrue("Expected timeout failure", failedResult.isFailure)
        assertTrue(
            "Exception should be BleTimeoutException",
            failedResult.exceptionOrNull() is BleTimeoutException
        )

        // Verify the next operation executes normally without being blocked
        val nextResult = queue.execute {
            delay(10.milliseconds)
            "recovered"
        }
        assertEquals("recovered", nextResult)
    }

    @Test
    fun timeoutShouldReportTheOperationItWasRunningNotAGenericWrite() = runBlocking {
        val queue = SerializedGattQueue(timeout = 50.milliseconds)

        val failure = runCatching {
            queue.execute("read of ${SabGattUuids.CHAR_SECURITY_STATUS}") { delay(300.milliseconds) }
        }.exceptionOrNull()

        assertEquals(
            "read of a65b7152-589e-4933-a426-8f729bfad439 timed out after 50ms",
            failure?.message
        )
    }
}
