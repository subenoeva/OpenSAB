package com.opensab.sab.session.coroutines

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunCatchingCancellableTest {

    @Test
    fun cancellationReachesTheCallerInsteadOfBecomingAFailure() {
        var caught: Throwable? = null
        try {
            runCatchingCancellable { throw CancellationException("scope closed") }
        } catch (cancellation: CancellationException) {
            caught = cancellation
        }
        assertTrue("cancellation must be rethrown, not wrapped in a Result", caught is CancellationException)
    }

    @Test
    fun anyOtherThrowableStillBecomesAFailure() {
        val result = runCatchingCancellable { throw IllegalStateException("boom") }
        assertTrue("everything that is not cancellation is still a Result", result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun aCancelledJobProducesNoResultAtAll() = runTest {
        val started = CompletableDeferred<Unit>()
        var outcome: Result<Unit>? = null

        val job = launch {
            outcome = runCatchingCancellable {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancelAndJoin()

        assertNull("a cancelled body must not settle into a Result", outcome)
    }
}
