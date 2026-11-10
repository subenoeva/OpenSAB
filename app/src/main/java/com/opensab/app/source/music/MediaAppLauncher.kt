package com.opensab.app.source.music

import com.opensab.domain.music.MediaSessionSource
import com.opensab.domain.music.PlaybackStatus
import com.opensab.logging.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

enum class LauncherState {
    IDLE,
    CONNECTING,    // IPC handshake & cold start in progress (repeated play presses debounced)
    BUFFERING,
    ACTIVE
}

interface MediaAppLauncher {
    val launcherState: StateFlow<LauncherState>

    fun launchAndPlay(
        packageName: String,
        serviceClassName: String? = null,
        onComplete: ((Boolean) -> Unit)? = null
    )

    fun reset()
}

class AndroidMediaAppLauncher(
    private val wakeLockSeam: WakeLockSeam,
    private val browserFactory: MediaBrowserClientFactory,
    private val audioKeyDispatcher: AudioKeyDispatcher,
    private val coroutineScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val appOpener: AppOpenerSeam? = null,
    private val sessionSource: MediaSessionSource? = null,
    /**
     * The media ID to dispatch for the package being launched, or null for none. The caller
     * resolves the package match, so the launcher never sees a pin belonging to another app.
     * Suspends rather than blocks: it is read from a `MediaBrowserCompat` callback on the main thread.
     */
    private val coldStartMediaIdProvider: suspend (String) -> String? = { null }
) : MediaAppLauncher {

    /**
     * Where an attempt stands in the cascade. The order is the point: an attempt only ever moves
     * down this list, so every guard in this class is one comparison against it.
     *
     * ```
     * CONNECTING -> PINNED -> BROWSING                     Level 2, through the MediaBrowser
     *   any of those -> OPENING_APP -> AWAITING_SESSION    Level 2.5, the app's own session
     *   OPENING_APP or AWAITING_SESSION -> MEDIA_KEY       Level 3, the global play key
     * any phase -> FINISHED
     * ```
     *
     * Buffering and the 1.5 s PLAYING confirmation are not phases: the player reports them in any
     * of the browser phases, and their deadlines are the jobs below.
     */
    private enum class Phase {
        CONNECTING,
        PINNED,
        BROWSING,
        OPENING_APP,
        AWAITING_SESSION,
        MEDIA_KEY,
        FINISHED;

        /** Whether callbacks from the MediaBrowser still speak for the attempt. */
        val usesBrowser: Boolean get() = this <= BROWSING
    }

    private class Attempt(
        val packageName: String,
        val onComplete: ((Boolean) -> Unit)?
    ) {
        @Volatile
        var phase: Phase = Phase.CONNECTING
            private set

        /** Moves forward only; asking for the current phase or an earlier one is a no-op. */
        fun advanceTo(next: Phase, reason: String? = null) {
            if (next <= phase) return
            Log.i("Media launcher $packageName: $phase -> $next" + (reason?.let { " ($it)" } ?: ""))
            phase = next
        }

        var playDispatchCount: Int = 0
        var lastDispatchedStatus: PlaybackStatus? = null
    }

    private val _launcherState = MutableStateFlow(LauncherState.IDLE)
    override val launcherState: StateFlow<LauncherState> = _launcherState.asStateFlow()

    // Confined to [dispatcher].
    private var activeConnection: MediaBrowserConnection? = null
    private var currentAttempt: Attempt? = null
    private var sessionStarted = false

    private var attemptWatchdogJob: Job? = null
    private var connectionWatchdogJob: Job? = null
    private var browseWatchdogJob: Job? = null
    private var pinReadJob: Job? = null
    private var bufferingWatchdogJob: Job? = null
    private var awaitSessionWatchdogJob: Job? = null
    private var sessionObserverJob: Job? = null
    private var playingConfirmationJob: Job? = null

    private fun isCurrent(attempt: Attempt): Boolean =
        (currentAttempt === attempt && attempt.phase != Phase.FINISHED)

    /**
     * The guard for every MediaBrowser callback. The browser is disconnected on escalation, but the
     * platform posts that disconnect, so callbacks already queued still arrive afterwards.
     */
    private fun isBrowsing(attempt: Attempt): Boolean =
        isCurrent(attempt) && attempt.phase.usesBrowser

    /**
     * Orders the containers worth drilling into. Player roots list their home page first, so
     * taking index 0 replays whatever track the home feed happens to recommend; containers
     * whose ID marks them as listening history come first instead.
     */
    private fun rankContainers(children: List<MediaBrowserItem>): List<MediaBrowserItem> {
        val (recent, rest) = children.filter { it.isBrowsable }.partition { item ->
            val id = item.mediaId.lowercase()
            RECENT_CONTAINER_TOKENS.any { id.contains(it) }
        }
        return recent + rest
    }

    /**
     * Drills into [containers] in order until one yields a playable track. An empty history
     * container must not end the attempt: a cold session has no queue, so falling through to
     * [MediaBrowserConnection.play] resumes nothing.
     */
    private fun drillInto(
        attempt: Attempt,
        connection: MediaBrowserConnection,
        containers: List<MediaBrowserItem>,
        index: Int
    ) {
        val container = containers.getOrNull(index)
        if (container == null) {
            connection.play()
            return
        }

        Log.i("Level 2: Browsable container detected (${container.mediaId}). Drilling down...")
        connection.subscribe(container.mediaId, object : MediaBrowserSubscriptionCallback {
            override fun onChildrenLoaded(parentId: String, children: List<MediaBrowserItem>) {
                if (!isBrowsing(attempt)) return
                connection.unsubscribe(parentId)

                val track = children.firstOrNull { it.isPlayable }
                if (track != null) {
                    Log.i("Level 2: Recent track found (${track.mediaId}). Playing.")
                    connection.playFromMediaId(track.mediaId)
                } else {
                    Log.i("Level 2: Container ${container.mediaId} holds no playable track. Trying next.")
                    drillInto(attempt, connection, containers, index + 1)
                }
            }

            override fun onError(parentId: String) {
                if (!isBrowsing(attempt)) return
                connection.unsubscribe(parentId)
                drillInto(attempt, connection, containers, index + 1)
            }
        })
    }

    /** Resolves browse root ID, preferring non-default identifiers. */
    private fun resolveRoot(connection: MediaBrowserConnection, ready: BrowserReady?): String {
        val announced = ready?.rootId?.takeIf { it.isNotBlank() && it != "root" }
        if (announced != null) return announced

        val fromConnection = connection.rootId.takeIf { it.isNotBlank() }
        return fromConnection ?: ready?.rootId?.takeIf { it.isNotBlank() } ?: "root"
    }

    override fun launchAndPlay(
        packageName: String,
        serviceClassName: String?,
        onComplete: ((Boolean) -> Unit)?
    ) {
        // Synchronous state update for debounce; rest runs on [dispatcher].
        _launcherState.value = LauncherState.CONNECTING
        coroutineScope.launch(dispatcher) {
            startAttempt(packageName, serviceClassName, onComplete)
        }
    }

    private fun startAttempt(
        packageName: String,
        serviceClassName: String?,
        onComplete: ((Boolean) -> Unit)?
    ) {
        Log.i("Level 2: Requesting headless cold start for $packageName")
        resetInternal()

        val attempt = Attempt(
            packageName = packageName,
            onComplete = onComplete
        )
        currentAttempt = attempt
        _launcherState.value = LauncherState.CONNECTING

        wakeLockSeam.acquire(10_000L)

        val connection = browserFactory.createConnection(packageName, serviceClassName)
        activeConnection = connection

        // Global attempt timeout (30s): guarantees termination even under silent server or infinite buffering
        attemptWatchdogJob = coroutineScope.launch(dispatcher) {
            delay(30_000.milliseconds)
            if (isCurrent(attempt)) {
                Log.w("Global attempt deadline (30s) reached for $packageName")
                finishAttempt(attempt, success = false)
            }
        }

        connectionWatchdogJob = coroutineScope.launch(dispatcher) {
            delay(5_000.milliseconds)
            if (isCurrent(attempt)) {
                Log.w("Level 2 connection watchdog triggered (5000 ms) for $packageName. Escalating.")
                escalate(attempt)
            }
        }

        connection.registerPlaybackStateCallback { state ->
            if (isBrowsing(attempt)) {
                handlePlaybackStateChanged(attempt, state)
            }
        }

        connection.connect(object : MediaBrowserConnectionCallback {
            override fun onConnected() {
                val effectiveRoot = resolveRoot(connection, null)
                onConnected(BrowserReady(rootId = effectiveRoot))
            }

            override fun onConnected(ready: BrowserReady) {
                if (!isBrowsing(attempt)) return

                connectionWatchdogJob?.cancel()
                connectionWatchdogJob = null
                Log.i("Level 2: MediaBrowserCompat connected successfully to $packageName with root ${ready.rootId}")

                // Already playing; finish immediately without modifying playlist
                if (ready.initialState == 3 /* PlaybackStateCompat.STATE_PLAYING */) {
                    Log.i("Level 2: Remote player is already in STATE_PLAYING. Preserving current playback.")
                    finishAttempt(attempt, success = true)
                    return
                }

                if (attempt.phase == Phase.CONNECTING) {
                    attempt.advanceTo(Phase.PINNED)
                    pinReadJob = coroutineScope.launch(dispatcher) {
                        val pinnedMediaId = coldStartMediaIdProvider(packageName)
                        if (!isBrowsing(attempt)) return@launch
                        if (pinnedMediaId == null) {
                            browseTree(ready)
                            return@launch
                        }
                        Log.i("Level 2: Dispatching pinned media ID ($pinnedMediaId) for $packageName")
                        connection.playFromMediaId(pinnedMediaId)

                        // The pinned branch subscribes to nothing, so it has to arm the deadline
                        // itself; otherwise a silent player would wait out the 30s global timeout
                        // instead of retrying through the tree.
                        browseWatchdogJob?.cancel()
                        browseWatchdogJob = coroutineScope.launch(dispatcher) {
                            delay(10_000.milliseconds)
                            if (isCurrent(attempt)) {
                                Log.w("Pinned media ID did not reach STATE_PLAYING. Falling back to the tree.")
                                onConnected(ready)
                            }
                        }
                    }
                    return
                }

                browseTree(ready)
            }

            private fun browseTree(ready: BrowserReady) {
                attempt.advanceTo(Phase.BROWSING)
                browseWatchdogJob?.cancel()
                browseWatchdogJob = coroutineScope.launch(dispatcher) {
                    delay(10_000.milliseconds)
                    if (isCurrent(attempt)) {
                        Log.w("Level 2 browse/play watchdog triggered (10s) for $packageName. Escalating.")
                        escalate(attempt)
                    }
                }

                val effectiveRoot = resolveRoot(connection, ready)
                connection.subscribe(effectiveRoot, object : MediaBrowserSubscriptionCallback {
                    override fun onChildrenLoaded(
                        parentId: String,
                        children: List<MediaBrowserItem>
                    ) {
                        if (!isBrowsing(attempt)) return
                        connection.unsubscribe(effectiveRoot)

                        val playable = children.firstOrNull { it.isPlayable }
                        if (playable != null) {
                            Log.i("Level 2: Direct playable track found (${playable.mediaId}). Playing.")
                            connection.playFromMediaId(playable.mediaId)
                            return
                        }

                        drillInto(attempt, connection, rankContainers(children), 0)
                    }

                    override fun onError(parentId: String) {
                        if (!isBrowsing(attempt)) return
                        connection.unsubscribe(effectiveRoot)
                        connection.play()
                    }
                })
            }

            override fun onConnectionFailed() {
                if (!isBrowsing(attempt)) return
                Log.w("Level 2: Connection failed in MediaBrowserCompat with $packageName")
                escalate(attempt)
            }

            override fun onConnectionSuspended() {
                Log.d("Level 2: Connection suspended with $packageName")
            }
        })
    }

    private fun handlePlaybackStateChanged(attempt: Attempt, state: Int) {
        when (state) {
            6 -> {
                Log.d("Level 2: Transitioned to STATE_BUFFERING")

                playingConfirmationJob?.cancel()
                playingConfirmationJob = null

                // Buffering started: the buffering watchdog takes over the deadline.
                browseWatchdogJob?.cancel()
                browseWatchdogJob = null

                _launcherState.value = LauncherState.BUFFERING

                // Renew WakeLock to 15s so kernel keeps CPU awake while buffering
                wakeLockSeam.acquire(15_000L)

                bufferingWatchdogJob?.cancel()
                bufferingWatchdogJob = coroutineScope.launch(dispatcher) {
                    delay(15_000.milliseconds)
                    if (isCurrent(attempt)) {
                        Log.w("Adaptive buffering timeout exceeded (15000 ms)")
                        escalate(attempt)
                    }
                }
            }

            3 -> {
                Log.i("Level 2: STATE_PLAYING reached. Starting 1500ms confirmation window.")
                bufferingWatchdogJob?.cancel()
                bufferingWatchdogJob = null
                playingConfirmationJob?.cancel()
                playingConfirmationJob = coroutineScope.launch(dispatcher) {
                    delay(1_500.milliseconds)
                    if (isCurrent(attempt)) {
                        Log.i("Level 2: STATE_PLAYING confirmed stable. Unbinding IPC and releasing WakeLock.")
                        finishAttempt(attempt, success = true)
                    }
                }
            }

            7 -> {
                Log.w("Level 2: Remote player reported STATE_ERROR. Escalating to Level 3.")
                playingConfirmationJob?.cancel()
                playingConfirmationJob = null
                escalate(attempt)
            }
        }
    }

    private fun escalate(attempt: Attempt) {
        if (!isBrowsing(attempt)) return
        attempt.advanceTo(Phase.OPENING_APP)

        cancelBrowserJobs()
        activeConnection?.disconnect()
        activeConnection = null

        val target = attempt.packageName
        var openerRequested = false
        if (appOpener != null) {
            val openResult = appOpener.openApp(target)
            Log.i("AppOpener attempted for $target, result=$openResult")
            if (openResult == OpenRequestResult.REQUESTED) {
                openerRequested = true
            }
        }

        if (openerRequested && sessionSource != null) {
            awaitSession(attempt)
            return
        }

        escalateToLevel3(attempt)
    }

    private fun escalateToLevel3(attempt: Attempt) {
        if (attempt.phase >= Phase.MEDIA_KEY) return
        attempt.advanceTo(Phase.MEDIA_KEY)

        cancelSessionJobs()
        stopSessionSource()

        val target = attempt.packageName
        val lastActive = audioKeyDispatcher.getLastActiveMediaPackage()

        // Strict isolation clause: prevent playing foreign audio when a favorite app is configured
        if (target.isNotBlank() && lastActive != target) {
            Log.w("Level 3 skipped for safety: audio focus ($lastActive) does not match requested favorite app ($target)")
            finishAttempt(attempt, success = false)
            return
        }

        Log.i("Level 3: Dispatching global media play key event (KEYCODE_MEDIA_PLAY)")
        audioKeyDispatcher.dispatchPlayKey()
        // Accepted key does not constitute confirmed playback
        finishAttempt(attempt, success = false)
    }

    private fun awaitSession(attempt: Attempt) {
        val target = attempt.packageName
        val source = sessionSource ?: run {
            escalateToLevel3(attempt)
            return
        }
        attempt.advanceTo(Phase.AWAITING_SESSION)

        cancelSessionJobs()

        wakeLockSeam.acquire(8_000L)

        source.setPreferredPackage(target)
        if (!sessionStarted) {
            sessionStarted = true
            source.start()
        }

        val initialSnapshot = source.activeSession.value
        attempt.playDispatchCount = 0
        attempt.lastDispatchedStatus = null

        awaitSessionWatchdogJob = coroutineScope.launch(dispatcher) {
            delay(7_000.milliseconds)
            if (isCurrent(attempt)) {
                Log.w("awaitSession watchdog timeout (7000 ms) reached for $target. Escalating to Level 3.")
                escalateToLevel3(attempt)
            }
        }

        sessionObserverJob = coroutineScope.launch(dispatcher) {
            // Discard initial StateFlow replay; only handle new post-launch emissions
            var replayConsumed = false

            source.activeSession.collect { snapshot ->
                if (!replayConsumed) {
                    replayConsumed = true
                    if (snapshot === initialSnapshot && snapshot?.status != PlaybackStatus.PLAYING) {
                        return@collect
                    }
                }

                if (!isCurrent(attempt)) return@collect
                if (snapshot == null) return@collect

                // Strict isolation: ignore sessions from other apps
                if (snapshot.packageName != target) return@collect

                when (snapshot.status) {
                    PlaybackStatus.PLAYING -> {
                        if (playingConfirmationJob?.isActive != true) {
                            Log.i("awaitSession: Target $target announced PLAYING. Starting 1500ms confirmation window.")
                            playingConfirmationJob = coroutineScope.launch(dispatcher) {
                                delay(1_500.milliseconds)
                                if (isCurrent(attempt)) {
                                    Log.i("awaitSession: Target $target confirmed PLAYING.")
                                    finishAttempt(attempt, success = true)
                                }
                            }
                        }
                    }

                    PlaybackStatus.PAUSED, PlaybackStatus.NONE, PlaybackStatus.STOPPED -> {
                        playingConfirmationJob?.cancel()
                        playingConfirmationJob = null

                        val canDispatch = when (attempt.playDispatchCount) {
                            0 -> true
                            1 if attempt.lastDispatchedStatus == PlaybackStatus.NONE &&
                                snapshot.status != PlaybackStatus.NONE -> true
                            else -> false
                        }

                        if (canDispatch) {
                            attempt.playDispatchCount++
                            attempt.lastDispatchedStatus = snapshot.status
                            Log.i("awaitSession: Target $target appeared in ${snapshot.status} (dispatch #${attempt.playDispatchCount}). Dispatching directed play().")
                            source.play()
                        }
                    }
                }
            }
        }
    }

    private fun finishAttempt(attempt: Attempt, success: Boolean) {
        if (!isCurrent(attempt)) return
        attempt.advanceTo(Phase.FINISHED, if (success) "playing" else "gave up")
        currentAttempt = null

        tearDown(if (success) LauncherState.ACTIVE else LauncherState.IDLE)

        // Notify after cleanup to avoid tearing down a relaunch attempt
        attempt.onComplete?.invoke(success)
    }

    override fun reset() {
        coroutineScope.launch(dispatcher) { resetInternal() }
    }

    private fun resetInternal() {
        val pending = currentAttempt?.takeIf { it.phase != Phase.FINISHED }
        pending?.advanceTo(Phase.FINISHED, "reset")
        currentAttempt = null

        tearDown(LauncherState.IDLE)

        pending?.onComplete?.invoke(false)
    }

    /** The one way an attempt gives its resources back, whether it finished or was reset. */
    private fun tearDown(finalState: LauncherState) {
        // WakeLock first, so a throwing cancellation cannot leave the kernel lock held
        wakeLockSeam.release()
        stopSessionSource()

        _launcherState.value = finalState

        attemptWatchdogJob?.cancel()
        attemptWatchdogJob = null
        cancelBrowserJobs()
        cancelSessionJobs()

        activeConnection?.disconnect()
        activeConnection = null
    }

    /** Level 2's deadlines, plus the PLAYING confirmation, which either level may have armed. */
    private fun cancelBrowserJobs() {
        playingConfirmationJob?.cancel()
        playingConfirmationJob = null
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = null
        browseWatchdogJob?.cancel()
        browseWatchdogJob = null
        pinReadJob?.cancel()
        pinReadJob = null
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = null
    }

    /** Level 2.5's deadline and observer, plus the PLAYING confirmation. */
    private fun cancelSessionJobs() {
        playingConfirmationJob?.cancel()
        playingConfirmationJob = null
        awaitSessionWatchdogJob?.cancel()
        awaitSessionWatchdogJob = null
        sessionObserverJob?.cancel()
        sessionObserverJob = null
    }

    private fun stopSessionSource() {
        if (sessionStarted) {
            sessionStarted = false
            sessionSource?.stop()
        }
    }

    private companion object {
        // Substrings players use in the media IDs of their listening-history containers
        // (Tidal: RECENTLY_PLAYED::.../CONTINUE_LISTEN_TO/...).
        val RECENT_CONTAINER_TOKENS = listOf("recent", "continue", "resume", "history")
    }
}
