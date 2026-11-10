package com.opensab.sab.session.coroutines

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * True when [this] is the calling coroutine being cancelled, which is how every notification
 * subscription of a session ends and is not trouble.
 *
 * The type alone does not say so: a `withTimeout` further down throws a `CancellationException`
 * too, into a coroutine that is still active, and that one is a failure the caller must report.
 */
suspend fun Throwable.isCancellationOfTheCaller(): Boolean =
    this is CancellationException && !currentCoroutineContext().isActive
