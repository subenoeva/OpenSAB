package com.opensab.domain.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [VolumeSource] double that records every action a page asks of it.
 *
 * **Owner: the mirror contract harness.**
 *
 * [totalCalls] is the aggregate the mirror contract harness reads to answer "did this press do
 * anything at all", without having to enumerate the methods of each source.
 */
class RecordingVolumeSource : VolumeSource {

    val state = MutableStateFlow(VolumeLevel())
    override val level: StateFlow<VolumeLevel> = state.asStateFlow()

    var raiseCalls = 0
        private set
    var lowerCalls = 0
        private set
    var muteCalls = 0
        private set

    val totalCalls: Int
        get() = raiseCalls + lowerCalls + muteCalls

    override fun start() = Unit

    override fun stop() = Unit

    override fun raise() { raiseCalls++ }

    override fun lower() { lowerCalls++ }

    override fun toggleMute() { muteCalls++ }
}

/**
 * [MusicManager] double that records every transport and volume command a page issues.
 *
 * **Owner: the mirror contract harness.**
 *
 * `pause()` keeps no named counter because no `onInput` branch routes to it, but it still feeds
 * [totalCalls], as does `play()` (also counted by [playCalls]), so the aggregate never under-reports
 * an action that did happen.
 */
class RecordingMusicManager : MusicManager {

    val state = MutableStateFlow(MusicState())
    override val currentState: StateFlow<MusicState> = state.asStateFlow()

    var playPauseCalls = 0
        private set
    var nextCalls = 0
        private set
    var prevCalls = 0
        private set
    var volUpCalls = 0
        private set
    var volDownCalls = 0
        private set
    var muteCalls = 0
        private set
    var resetCalls = 0
        private set
    var playCalls = 0
        private set

    private var directTransportCalls = 0

    val totalCalls: Int
        get() = playPauseCalls + nextCalls + prevCalls + volUpCalls + volDownCalls + muteCalls +
            directTransportCalls + resetCalls

    override fun start(scope: CoroutineScope) = Unit

    override fun stop() = Unit

    override fun play() {
        playCalls++
        directTransportCalls++
    }

    override fun pause() { directTransportCalls++ }

    override fun togglePlayPause() { playPauseCalls++ }

    override fun skipToNext() { nextCalls++ }

    override fun skipToPrevious() { prevCalls++ }

    override fun volumeUp() { volUpCalls++ }

    override fun volumeDown() { volDownCalls++ }

    override fun toggleMute() { muteCalls++ }

    override fun reset() { resetCalls++ }
}

/** **Owner: the test that constructs it.** */
class MockMediaControllerAdapter : MediaControllerAdapter {

    private val _musicState = MutableStateFlow(MusicState())
    override val musicState: StateFlow<MusicState> = _musicState.asStateFlow()

    var playCount = 0
        private set
    var pauseCount = 0
        private set
    var skipNextCount = 0
        private set
    var skipPrevCount = 0
        private set
    var startCount = 0
        private set
    var stopCount = 0
        private set
    var resetCount = 0
        private set

    override fun start(scope: CoroutineScope) {
        startCount++
    }

    override fun stop() {
        stopCount++
    }

    fun setTrack(metadata: TrackMetadata) {
        _musicState.value = _musicState.value.copy(metadata = metadata)
    }

    /**
     * A redraw with nothing to say about it: a new track title, distinct from the last one so the
     * `StateFlow` actually emits and `RootMenuPageProvider.redrawOn` fires. For tests that only need
     * to trigger the engine's redraw path a known number of times.
     */
    fun emitPlaying(sequence: Int) {
        setTrack(TrackMetadata(title = "Track $sequence"))
    }

    fun setStatus(status: PlaybackStatus) {
        _musicState.value = _musicState.value.copy(status = status)
    }

    override fun play() {
        playCount++
        _musicState.value = _musicState.value.copy(status = PlaybackStatus.PLAYING)
    }

    override fun pause() {
        pauseCount++
        _musicState.value = _musicState.value.copy(status = PlaybackStatus.PAUSED)
    }

    override fun togglePlayPause() {
        if (_musicState.value.status == PlaybackStatus.PLAYING) {
            pause()
        } else {
            play()
        }
    }

    override fun skipToNext() {
        skipNextCount++
    }

    override fun skipToPrevious() {
        skipPrevCount++
    }

    override fun volumeUp() {
        val current = _musicState.value.volumePercent
        val newVol = (current + 10).coerceAtMost(100)
        _musicState.value = _musicState.value.copy(volumePercent = newVol)
    }

    override fun volumeDown() {
        val current = _musicState.value.volumePercent
        val newVol = (current - 10).coerceAtLeast(0)
        _musicState.value = _musicState.value.copy(volumePercent = newVol)
    }

    override fun toggleMute() {
        val currentMute = _musicState.value.isMuted
        _musicState.value = _musicState.value.copy(isMuted = !currentMute)
    }

    override fun reset() {
        resetCount++
    }
}

/** **Owner: the test that constructs it.** */
class FakeMediaLibraryBrowser : MediaLibraryBrowser {
    var rootId: String? = "root"
    val children: MutableMap<String, List<MediaLibraryNode>> = mutableMapOf()

    var connectCount: Int = 0
        private set
    var disconnectCount: Int = 0
        private set
    var lastConnectedPackage: String? = null
        private set

    private var connected = false

    override suspend fun connect(packageName: String): String? {
        connectCount++
        lastConnectedPackage = packageName
        val root = rootId
        connected = root != null
        return root
    }

    override suspend fun browse(parentId: String): List<MediaLibraryNode> {
        if (!connected) return emptyList()
        return children[parentId] ?: emptyList()
    }

    override fun disconnect() {
        disconnectCount++
        connected = false
    }
}
