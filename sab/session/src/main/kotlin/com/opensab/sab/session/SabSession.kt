package com.opensab.sab.session

import com.opensab.logging.Log
import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One connection to one BTU, from an accepted START to its close.
 *
 * Owner: [SabSessionController]. It is the only class that creates a session and the only one that
 * closes it; everything else receives the session and at most asks for its close through
 * [requestClose].
 *
 * [scope] runs one coroutine at a time (the parent dispatcher's `limitedParallelism(1)`, when the
 * parent supports it — see [confinedDispatcherOver]), so state mutated from the engine's collectors
 * needs no lock — the logical thread can still hop physical threads between suspensions. [close]
 * runs the actions registered with [onClose], last registered first, on that same confined
 * dispatcher but under [NonCancellable], then cancels [scope] and waits for it to finish.
 */
class SabSession(
    val id: Long,
    val macAddress: String,
    val origin: SessionOrigin,
    parent: CoroutineContext,
    private val stallThreshold: Duration?,
    private val onCloseRequested: (SabSession, CloseReason) -> Unit,
) {
    private val confinedDispatcher: CoroutineDispatcher = confinedDispatcherOver(
        parent[ContinuationInterceptor] as? CoroutineDispatcher
            ?: error("SabSession requires a CoroutineDispatcher in its parent context")
    )

    val scope: CoroutineScope = CoroutineScope(
        parent + SupervisorJob(parent[Job]) + confinedDispatcher + CoroutineName("SabSession#$id")
    )

    /** Set once by the controller after the pipeline connects. */
    var connected: SabSessionState.Connected? = null
        internal set

    @Volatile
    var isClosed: Boolean = false
        private set

    private val closeActions = mutableListOf<Pair<String, suspend () -> Unit>>()

    init {
        Log.i("SabSession #%d opened for %s (origin=%s)", id, macAddress, origin)
        if (stallThreshold != null) {
            scope.launch { watchForStalls(stallThreshold) }
        }
    }

    private suspend fun watchForStalls(threshold: Duration) {
        var lastTick = System.nanoTime()
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(1.seconds)
            val now = System.nanoTime()
            val lateMs = (now - lastTick) / 1_000_000 - 1_000
            if (lateMs > threshold.inWholeMilliseconds) {
                Log.w("SabSession #%d thread stalled for %d ms", id, lateMs)
            }
            lastTick = now
        }
    }

    /** Registers an action to run, in reverse registration order, when the session closes. */
    fun onClose(name: String, action: suspend () -> Unit) {
        synchronized(closeActions) { closeActions.add(name to action) }
    }

    /** For the link watcher and anything else that only gets to ask: the owner decides. */
    fun requestClose(reason: CloseReason) {
        onCloseRequested(this, reason)
    }

    /** Runs [block] on the session's confined dispatcher. */
    suspend fun <T> confined(block: suspend CoroutineScope.() -> T): T =
        withContext(confinedDispatcher, block)

    internal suspend fun close(reason: CloseReason) {
        if (isClosed) return
        isClosed = true

        val actions = synchronized(closeActions) { closeActions.toList() }.asReversed()
        Log.i("SabSession #%d closing (%s), %d close actions", id, reason, actions.size)
        val start = System.currentTimeMillis()

        try {
            withContext(confinedDispatcher + NonCancellable) {
                for ((name, action) in actions) {
                    runCatchingCancellable { action() }
                        .onFailure { Log.w(it, "SabSession #%d close action %s failed", id, name) }
                }
            }
            // The session log's "closed in" line is what ends a session for anyone reading the log; a
            // cancelled caller must not be able to skip it.
            withContext(NonCancellable) {
                scope.coroutineContext.job.cancel()
                scope.coroutineContext.job.join()
            }
        } finally {
            Log.i("SabSession #%d closed in %d ms", id, System.currentTimeMillis() - start)
        }
    }
}

/**
 * `limitedParallelism(1)` forwards every real piece of work to [dispatcher]'s own `dispatch()`.
 * `UnconfinedTestDispatcher` rejects any `dispatch()` call that
 * does not come from its own `yield()` machinery, wrapped in `kotlinx.coroutines.DispatchException`
 * — a class `internal` to kotlinx-coroutines-core, so it cannot be named here, and importing
 * `kotlinx-coroutines-test` into production code to type-check for it is worse. A single throwaway
 * probe dispatch settles it without either: a dispatcher that rejects it is already single-threaded
 * and deterministic (true of every `TestDispatcher`), so running the session unwrapped on it keeps
 * the same "one coroutine at a time" guarantee that `limitedParallelism(1)` gives a real thread pool.
 */
private fun confinedDispatcherOver(dispatcher: CoroutineDispatcher): CoroutineDispatcher {
    val limited = dispatcher.limitedParallelism(1)
    val dispatchesDirectly = runCatchingCancellable { limited.dispatch(EmptyCoroutineContext, NO_OP) }.isSuccess
    return if (dispatchesDirectly) limited else dispatcher
}

private val NO_OP = Runnable {}
