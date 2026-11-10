package com.opensab.sab.session.coroutines

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] that lets cancellation through.
 *
 * `runCatching` catches `Throwable`, and inside a coroutine that includes the
 * `CancellationException` the machinery throws to unwind a cancelled job. Swallowed, it turns a
 * cancelled operation into a `Result.failure` the caller reads as a protocol error: the session
 * publishes a failed connection for a link the rider closed, and the coroutine that was supposed
 * to stop carries on to the next line.
 *
 * `inline` is what lets the body suspend: the lambda is inlined into the caller, so a `suspend fun`
 * keeps its suspension points inside the `try`.
 *
 * Every `suspend fun` that returns a `Result` uses this instead of a bare `runCatching`.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }
