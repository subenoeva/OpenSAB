package com.opensab.app.source.music

import com.opensab.domain.music.MediaControllerAdapter
import com.opensab.domain.music.MediaSessionSource
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.TrackMetadata
import com.opensab.domain.music.VolumeSource
import com.opensab.logging.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

sealed interface FavoriteSelection {
    data object Loading : FavoriteSelection
    data class Ready(val packageName: String?) : FavoriteSelection
}

class SystemMediaControllerAdapter(
    private val sessionSource: MediaSessionSource,
    private val volumeSource: VolumeSource,
    private val favoriteRepository: FavoriteMediaRepository? = null,
    private val appLauncher: MediaAppLauncher? = null,
    private val appScanner: MediaAppScanner? = null,
    private val audioKeyDispatcher: AudioKeyDispatcher? = null,
    private val applicationScope: CoroutineScope? = null
) : MediaControllerAdapter {

    private val _musicState = MutableStateFlow(MusicState())
    override val musicState: StateFlow<MusicState> = _musicState.asStateFlow()

    private var sessionJob: Job? = null
    private var volumeJob: Job? = null
    private var favoriteJob: Job? = null
    private var autonomousFavoriteJob: Job? = null
    private var level1EscalationJob: Job? = null

    @Volatile
    private var favoriteSelection: FavoriteSelection =
        if (favoriteRepository == null) FavoriteSelection.Ready(null) else FavoriteSelection.Loading

    private var isAttached = false
    private var attachedScope: CoroutineScope? = null

    init {
        // Collect favorite updates independently of BLE
        if (applicationScope != null && favoriteRepository != null) {
            autonomousFavoriteJob = applicationScope.launch {
                favoriteRepository.favoritePackage.collect { pkg ->
                    favoriteSelection = FavoriteSelection.Ready(pkg)
                    sessionSource.setPreferredPackage(pkg)
                }
            }
        }
    }

    override fun start(scope: CoroutineScope) {
        if (isAttached) stop()
        Log.i("Attaching to the system media session and volume sources")

        sessionSource.start()
        volumeSource.start()
        isAttached = true
        attachedScope = scope

        // Fallback subscription if autonomous scope is unavailable
        if (autonomousFavoriteJob == null && favoriteRepository != null) {
            favoriteJob = scope.launch {
                favoriteRepository.favoritePackage.collect { pkg ->
                    favoriteSelection = FavoriteSelection.Ready(pkg)
                    sessionSource.setPreferredPackage(pkg)
                }
            }
        }

        sessionJob = scope.launch {
            sessionSource.activeSession.collect { snapshot ->
                _musicState.update { current ->
                    current.copy(
                        metadata = snapshot?.metadata ?: TrackMetadata(),
                        status = snapshot?.status ?: PlaybackStatus.NONE
                    )
                }
            }
        }

        volumeJob = scope.launch {
            volumeSource.level.collect { level ->
                _musicState.update { current ->
                    current.copy(
                        volumePercent = level.percent,
                        isMuted = level.isMuted
                    )
                }
            }
        }
    }

    override fun stop() {
        if (!isAttached) return
        isAttached = false
        attachedScope = null

        appLauncher?.reset()

        level1EscalationJob?.cancel()
        level1EscalationJob = null
        favoriteJob?.cancel()
        favoriteJob = null
        sessionJob?.cancel()
        sessionJob = null
        volumeJob?.cancel()
        volumeJob = null

        sessionSource.stop()
        volumeSource.stop()
    }

    private var cachedFavoriteServiceClass: Pair<String, String?>? = null

    private fun resolveServiceClassForFavorite(fav: String): String? {
        val cached = cachedFavoriteServiceClass
        if (cached?.first == fav) {
            return cached.second
        }
        val scannedClass = appScanner?.getInstalledMediaApps()
            ?.firstOrNull { it.packageName == fav }
            ?.serviceClassName
        if (scannedClass != null) {
            cachedFavoriteServiceClass = fav to scannedClass
            return scannedClass
        }
        val defaultComponent = MediaAppScannerDefaults.resolveServiceComponent(fav)
        val defaultClass = defaultComponent?.className
        cachedFavoriteServiceClass = fav to defaultClass
        return defaultClass
    }

    override fun play() {
        Log.d("Media transport command: play")
        when (val selection = favoriteSelection) {
            is FavoriteSelection.Loading -> {
                // Wait for preferences before deciding
                val scope = applicationScope ?: attachedScope
                scope?.launch {
                    kotlinx.coroutines.withTimeoutOrNull(2_000.milliseconds) {
                        while (favoriteSelection is FavoriteSelection.Loading) {
                            delay(10.milliseconds)
                        }
                    }
                    (favoriteSelection as? FavoriteSelection.Ready)?.let { ready ->
                        performPlay(ready.packageName)
                    } ?: run {
                        performPlay(null)
                    }
                }
            }

            is FavoriteSelection.Ready -> {
                performPlay(selection.packageName)
            }
        }
    }

    private fun isLauncherBusy(): Boolean {
        val state = appLauncher?.launcherState?.value ?: LauncherState.IDLE
        return state == LauncherState.CONNECTING || state == LauncherState.BUFFERING
    }

    private fun performPlay(fav: String?) {
        val activeSnapshot = sessionSource.activeSession.value

        val isHealthy = activeSnapshot != null &&
                (activeSnapshot.status == PlaybackStatus.PLAYING || activeSnapshot.status == PlaybackStatus.PAUSED)

        // Ignore foreign sessions if favorite is configured
        val matchesFavorite = fav.isNullOrBlank() || activeSnapshot?.packageName == fav

        if (isHealthy && matchesFavorite) {
            Log.i("Level 1: Immediate resumption (0 ms) on healthy active session: ${activeSnapshot.packageName}")
            sessionSource.play()

            // If already playing, no need for cold-start escalation
            if (activeSnapshot.status == PlaybackStatus.PLAYING) {
                return
            }

            // Escalate to Level 2 if paused session fails to resume within 2s
            val scope = applicationScope ?: attachedScope
            if (!fav.isNullOrBlank() && appLauncher != null && scope != null) {
                level1EscalationJob?.cancel()
                level1EscalationJob = scope.launch {
                    delay(2_000.milliseconds)
                    val currentSnapshot = sessionSource.activeSession.value
                    val stillNotPlaying = currentSnapshot == null ||
                            currentSnapshot.status != PlaybackStatus.PLAYING
                    if (isAttached && stillNotPlaying && !isLauncherBusy()) {
                        Log.w("Level 1 session (${currentSnapshot?.packageName}) failed to resume within 2s. Escalating to Level 2 cold start for $fav")
                        val serviceClass = resolveServiceClassForFavorite(fav)
                        appLauncher.launchAndPlay(fav, serviceClass)
                    }
                }
            }
            return
        }

        // Debounce repeated play invocations while connecting or buffering
        if (isLauncherBusy()) {
            Log.d("Ignoring play command: launcher in state ${appLauncher?.launcherState?.value}")
            return
        }

        if (!fav.isNullOrBlank() && appLauncher != null) {
            Log.i("Level 2: Starting cold start for favorite app: $fav")
            val serviceClass = resolveServiceClassForFavorite(fav)
            appLauncher.launchAndPlay(fav, serviceClass)
        } else {
            // Fallback when no favorite is configured
            if (audioKeyDispatcher != null) {
                Log.i("Level 3: Dispatching universal media key (generic mode)")
                audioKeyDispatcher.dispatchPlayKey()
            } else {
                sessionSource.play()
            }
        }
    }

    override fun pause() {
        Log.d("Media transport command: pause")
        // A pending Level 1 watchdog would read this pause as a failed resume and cold-start the music.
        level1EscalationJob?.cancel()
        level1EscalationJob = null
        sessionSource.pause()
    }

    override fun togglePlayPause() {
        if (_musicState.value.status == PlaybackStatus.PLAYING) pause() else play()
    }

    override fun skipToNext() {
        Log.d("Media transport command: skip to next")
        sessionSource.skipToNext()
    }

    override fun skipToPrevious() {
        Log.d("Media transport command: skip to previous")
        sessionSource.skipToPrevious()
    }

    override fun volumeUp() {
        volumeSource.raise()
    }

    override fun volumeDown() {
        volumeSource.lower()
    }

    override fun toggleMute() {
        volumeSource.toggleMute()
    }

    override fun reset() {
        appLauncher?.reset()
    }
}

