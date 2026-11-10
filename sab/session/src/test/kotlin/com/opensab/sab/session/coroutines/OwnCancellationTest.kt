package com.opensab.sab.session.coroutines

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class OwnCancellationTest {

    @Test
    fun aCancelledCoroutineSeesItsOwnCancellation() = runTest {
        val started = CompletableDeferred<Unit>()
        var own: Boolean? = null
        val job = launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } catch (e: Throwable) {
                own = e.isCancellationOfTheCaller()
                throw e
            }
        }
        started.await()
        job.cancelAndJoin()

        assertEquals(true, own)
    }

    /** `withTimeout` throws a `CancellationException` into a coroutine nobody cancelled. */
    @Test
    fun aTimeoutFurtherDownIsNotTheCallersCancellation() = runTest {
        var own: Boolean? = null
        try {
            withTimeout(10.milliseconds) { awaitCancellation() }
        } catch (e: Throwable) {
            own = e.isCancellationOfTheCaller()
        }

        assertEquals(false, own)
    }

    @Test
    fun anOrdinaryFailureIsNotCancellation() = runTest {
        assertEquals(false, IllegalStateException("link lost").isCancellationOfTheCaller())
    }
}
