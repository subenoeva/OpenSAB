package com.opensab.sab.session

import com.opensab.logging.Log
import com.opensab.sab.session.ble.DeviceBonder
import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Owner of every [SabSession] and of [SabSessionState].
 *
 * [SessionCommand]s are serialized from one logical thread: [processCommands] drains [commands] one
 * at a time, but a [SessionCommand.Start] never blocks that loop behind a bond that can take up to
 * 30 seconds — the bond-link-pipeline attempt runs as a child of the controller's own scope
 * ([attemptJob]), so a [SessionCommand.Disconnect] arriving mid-bond cancels that job directly and
 * the next queued command is already being handled.
 *
 * Closes are serialized on the same loop: a [SabSession.requestClose] (link loss) and a failed
 * attempt enqueue a [Work.Close] instead of closing where they run, so a START queued behind a close
 * only opens once the old session has logged `closed in`.
 *
 * **Owner of the retry after a failed connection**: this class, and the same loop. An attempt that
 * fails once the motorcycle is bonded (the link would not open, or the pipeline broke) is tried again
 * as [reconnect] allows, but only while [presence] says the motorcycle is still linked: a timeout
 * alone does not say it is there. The wait is one [PendingRetry] with one timer on [scope]; a
 * [SessionCommand.Disconnect], a new [SessionCommand.Start] or the motorcycle leaving ends it, and the
 * retry opens its session from the loop like any START, so there are never two. A link lost after
 * connecting is not retried here.
 *
 * The one way into a session: `SabConnectionService` submits what the receiver, the scanner and the
 * dashboard ask for through `SessionLauncher`, and nothing else in the app can open or close a
 * session or publish its state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SabSessionController(
    private val bonder: DeviceBonder,
    private val links: LinkOpener,
    private val pipeline: SessionPipeline,
    private val participants: List<SessionParticipant>,
    private val parent: CoroutineContext,
    private val stallThreshold: Duration?,
    private val reconnect: ReconnectPolicy,
    private val presence: VehiclePresence,
) : SabSessions {

    private val _state = MutableStateFlow<SabSessionState>(SabSessionState.Disconnected)
    override val state: StateFlow<SabSessionState> = _state.asStateFlow()

    private val _idle = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** The `startId` of the last command, whenever nothing is live or pending. */
    val idle: SharedFlow<Int> = _idle.asSharedFlow()

    /** The controller's own logical thread. A fresh root, not a child of [parent]'s job. */
    private val scope = CoroutineScope(parent + SupervisorJob())
    private val commands = Channel<Work>(Channel.UNLIMITED)

    /** What [processCommands] drains: the commands callers submit, and the closes sessions request. */
    private sealed interface Work {
        data class Submitted(val command: SessionCommand) : Work
        data class Close(
            val session: SabSession,
            val reason: CloseReason,
            val failure: Throwable? = null,
            val retriable: Boolean = false
        ) : Work
        data class RetryDue(val retry: PendingRetry) : Work
        data class VehicleLeft(val retry: PendingRetry) : Work
    }

    /** The wait between a failed attempt and the next one. Compared by identity, never by value. */
    private class PendingRetry(
        val macAddress: String,
        val origin: SessionOrigin,
        val number: Int,
        val cause: Throwable
    ) {
        @Volatile
        var timer: Job? = null
    }

    /**
     * The one live session — mid-attempt, connected, or being replaced. `null` between sessions.
     * Written only by [processCommands]; `@Volatile` because [runAttempt] reads it from [attemptJob].
     * The "check the identity, then null it" pattern is what makes only the first arrival win a race
     * between a [SessionCommand.Disconnect] and a spontaneous link loss.
     */
    @Volatile
    private var liveSession: SabSession? = null

    /**
     * The bond-link-pipeline attempt for [liveSession], running on [scope] — never on
     * `session.scope` itself: [runAttempt]'s own failure branch calls [SabSession.close], which
     * cancels and joins `session.scope`, and a coroutine cannot join a job it is a child of without
     * cancelling itself out from under that very call. Cancelled by [closeAndPublishDisconnected]
     * before it closes the session it belongs to, so a [SessionCommand.Disconnect] mid-bond
     * interrupts [bonder] or [links] the same way closing interrupts anything already registered
     * with [SabSession.onClose].
     */
    @Volatile
    private var attemptJob: Job? = null

    /** The one pending retry, or null. Written only by [processCommands]; no session is live while it is set. */
    private var pendingRetry: PendingRetry? = null

    /** Retries spent since the last accepted START. Written only by [processCommands]. */
    private var retriesUsed = 0

    private val nextSessionId = AtomicLong(1)

    @Volatile
    private var lastStartId: Int = 0

    init {
        scope.launch { processCommands() }
    }

    /** Never suspends and never blocks: the caller (UI thread, receiver, service) fires and forgets. */
    fun submit(command: SessionCommand) {
        commands.trySend(Work.Submitted(command))
    }

    private suspend fun processCommands() {
        for (work in commands) {
            when (work) {
                is Work.Submitted -> handleCommand(work.command)
                is Work.Close -> handleClose(work)
                is Work.RetryDue -> handleRetryDue(work.retry)
                is Work.VehicleLeft -> handleVehicleLeft(work.retry)
            }
            checkIdle()
        }
    }

    private suspend fun handleCommand(command: SessionCommand) {
        val (kind, origin, startId) = when (command) {
            is SessionCommand.Start -> Triple("START", command.origin, command.startId)
            is SessionCommand.Disconnect -> Triple("DISCONNECT", command.origin, command.startId)
        }
        lastStartId = startId
        Log.i("Session command %s from %s (startId=%d)", kind, origin, startId)
        when (command) {
            is SessionCommand.Start -> handleStart(command)
            is SessionCommand.Disconnect -> handleDisconnect()
        }
    }

    private suspend fun handleStart(command: SessionCommand.Start) {
        val current = liveSession
        if (current != null) {
            if (current.macAddress.equals(command.macAddress, ignoreCase = true)) {
                Log.i(
                    "Session command %s ignored: %s",
                    "START",
                    "already live or connecting for ${command.macAddress}"
                )
                return
            }
            liveSession = null
            closeAndPublishDisconnected(current, CloseReason.REPLACED)
        }
        if (cancelPendingRetry()) {
            Log.i("Pending retry dropped: a START for %s arrived first", command.macAddress)
        }
        retriesUsed = 0
        openSession(command.macAddress, command.origin)
    }

    private fun openSession(macAddress: String, origin: SessionOrigin) {
        val session = SabSession(
            id = nextSessionId.getAndIncrement(),
            macAddress = macAddress,
            origin = origin,
            parent = parent,
            stallThreshold = stallThreshold,
            onCloseRequested = { requested, reason -> requestSessionClose(requested, reason) }
        )
        liveSession = session
        attemptJob = scope.launch { runAttempt(session) }
    }

    private suspend fun handleDisconnect() {
        val session = liveSession
        if (session == null) {
            if (cancelPendingRetry()) {
                Log.i("Pending retry dropped: disconnect requested")
                _state.value = SabSessionState.Disconnected
                return
            }
            // The receiver's DISCONNECT after a LINK_LOST already closed it.
            Log.i("Session command %s ignored: %s", "DISCONNECT", "no live session")
            return
        }
        liveSession = null
        closeAndPublishDisconnected(session, CloseReason.DISCONNECT)
    }

    /** [SabSession.requestClose] comes back here: the controller is the only class that may close. */
    private fun requestSessionClose(session: SabSession, reason: CloseReason) {
        commands.trySend(Work.Close(session, reason))
    }

    private suspend fun handleClose(close: Work.Close) {
        val session = close.session
        if (liveSession !== session) {
            Log.i("Session command %s ignored: %s", close.reason, "session #${session.id} is not the live one")
            return
        }
        liveSession = null
        val failure = close.failure
        if (failure == null) {
            closeAndPublishDisconnected(session, close.reason)
        } else {
            session.close(close.reason)
            if (!close.retriable || !scheduleRetry(session, failure)) {
                _state.value = SabSessionState.Error(failure)
            }
        }
    }

    /** False when the failure is final: the retries are spent, or the motorcycle is no longer linked. */
    private suspend fun scheduleRetry(failed: SabSession, failure: Throwable): Boolean {
        val number = retriesUsed + 1
        val wait = reconnect.delayBefore(number)
        if (wait == null) {
            if (reconnect.maxRetries > 0) {
                Log.w("Giving up on %s after %d retries", failed.macAddress, reconnect.maxRetries)
            }
            return false
        }
        if (!presence.linked(failed.macAddress).first()) {
            Log.i("Not retrying %s: the motorcycle is not linked", failed.macAddress)
            return false
        }
        retriesUsed = number
        val retry = PendingRetry(failed.macAddress, failed.origin, number, failure)
        pendingRetry = retry
        _state.value = SabSessionState.Connecting(ConnectionStep.WAITING_TO_RETRY)
        Log.i(
            "Retry %d of %d for %s in %d ms, while the motorcycle stays linked",
            number,
            reconnect.maxRetries,
            failed.macAddress,
            wait.inWholeMilliseconds
        )
        retry.timer = scope.launch {
            val left = withTimeoutOrNull(wait) { presence.linked(retry.macAddress).first { linked -> !linked } }
            commands.trySend(if (left == null) Work.RetryDue(retry) else Work.VehicleLeft(retry))
        }
        return true
    }

    private suspend fun handleRetryDue(retry: PendingRetry) {
        if (pendingRetry !== retry) return
        pendingRetry = null
        if (!presence.linked(retry.macAddress).first()) {
            abandon(retry)
            return
        }
        Log.i("Retry %d of %d for %s starting", retry.number, reconnect.maxRetries, retry.macAddress)
        openSession(retry.macAddress, retry.origin)
    }

    private fun handleVehicleLeft(retry: PendingRetry) {
        if (pendingRetry !== retry) return
        pendingRetry = null
        abandon(retry)
    }

    private fun abandon(retry: PendingRetry) {
        Log.i("Retry %d for %s abandoned: the motorcycle is no longer linked", retry.number, retry.macAddress)
        _state.value = SabSessionState.Error(retry.cause)
    }

    /** True if there was one. Its timer may already have queued its work, which the identity check drops. */
    private fun cancelPendingRetry(): Boolean {
        val retry = pendingRetry ?: return false
        pendingRetry = null
        retry.timer?.cancel()
        return true
    }

    private suspend fun runAttempt(session: SabSession) {
        var bonded = false
        val outcome = runCatchingCancellable {
            bonder.ensureBonded(session.macAddress).getOrThrow()
            bonded = true
            val transport = links.open(session.macAddress).getOrElse { cause ->
                throw TransportUnreachableException(session.macAddress, cause)
            }
            pipeline.open(session, transport) { step ->
                if (liveSession === session) {
                    _state.value = SabSessionState.Connecting(step)
                }
            }
        }
        outcome.onSuccess { connected ->
            attemptJob = null
            if (liveSession !== session) return@onSuccess
            session.connected = connected
            participants.forEach { participant ->
                participant.onSessionStarted(session)
                session.onClose(participant.name) { participant.onSessionClosing(session) }
            }
            Log.i(
                "SabSession #%d started %d participants: %s",
                session.id,
                participants.size,
                participants.joinToString(", ") { it.name }
            )
            _state.value = connected
            pipeline.announceOpened(session)
        }.onFailure { error ->
            attemptJob = null
            if (error is CancellationException) {
                // The session was closed out from under this attempt — a Disconnect mid-bond, or
                // REPLACED by another Start. Whoever closed it already published the state.
                return@onFailure
            }
            Log.e(error, "Connection pipeline failed, tearing down partial session")
            commands.trySend(Work.Close(session, CloseReason.PIPELINE_FAILED, failure = error, retriable = bonded))
        }
    }

    /** The rider-visible half of a close: [SessionPipeline.announceClosing] only if it was ever connected. */
    private suspend fun closeAndPublishDisconnected(session: SabSession, reason: CloseReason) {
        attemptJob?.cancelAndJoin()
        attemptJob = null
        if (session.connected != null) {
            pipeline.announceClosing(session)
        }
        session.close(reason)
        _state.value = SabSessionState.Disconnected
    }

    private fun checkIdle() {
        if (liveSession == null && pendingRetry == null && commands.isEmpty) {
            _idle.tryEmit(lastStartId)
        }
    }
}
