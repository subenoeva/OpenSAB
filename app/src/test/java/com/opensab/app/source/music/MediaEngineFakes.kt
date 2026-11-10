package com.opensab.app.source.music

import com.opensab.domain.music.MediaSessionSnapshot
import com.opensab.domain.music.MediaSessionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * With a [clock], the lock lapses when its timeout runs out, as the kernel's does; without one it
 * is held until released.
 */
class FakeWakeLockSeam(private val clock: (() -> Long)? = null) : WakeLockSeam {
    private var held = false
    private var expiresAtMs = Long.MAX_VALUE

    override val isHeld: Boolean
        get() = held && (clock == null || clock.invoke() < expiresAtMs)

    var lastTimeoutMs: Long? = null

    override fun acquire(timeoutMs: Long) {
        held = true
        lastTimeoutMs = timeoutMs
        expiresAtMs = clock?.invoke()?.plus(timeoutMs) ?: Long.MAX_VALUE
    }

    override fun release() {
        held = false
    }
}

class FakeMediaBrowserConnection : MediaBrowserConnection {
    override var rootId: String = "root"
    var isConnected = false
    var isDisconnected = false
    var connectionCallback: MediaBrowserConnectionCallback? = null
    var rootItems: List<MediaBrowserItem> = emptyList()
    var childItems: MutableMap<String, List<MediaBrowserItem>> = mutableMapOf()
    var unsubscribedCategories: MutableSet<String> = mutableSetOf()
    var lastPlayedMediaId: String? = null
    var genericPlayCalled = false
    var playbackCallback: ((Int) -> Unit)? = null

    override fun connect(callback: MediaBrowserConnectionCallback) {
        connectionCallback = callback
    }

    fun simulateConnected(ready: BrowserReady = BrowserReady(rootId)) {
        isConnected = true
        connectionCallback?.onConnected(ready)
    }

    fun simulateConnectionFailed() {
        isConnected = false
        connectionCallback?.onConnectionFailed()
    }

    fun simulatePlaybackState(state: Int) {
        playbackCallback?.invoke(state)
    }

    override fun disconnect() {
        isDisconnected = true
        isConnected = false
    }

    override fun subscribe(parentId: String, callback: MediaBrowserSubscriptionCallback) {
        if (parentId == rootId || parentId == "root" || parentId.isEmpty()) {
            callback.onChildrenLoaded(parentId, rootItems)
        } else {
            callback.onChildrenLoaded(parentId, childItems[parentId] ?: emptyList())
        }
    }

    override fun unsubscribe(parentId: String) {
        unsubscribedCategories.add(parentId)
    }

    override fun play() {
        genericPlayCalled = true
    }

    override fun playFromMediaId(mediaId: String) {
        lastPlayedMediaId = mediaId
    }

    override fun registerPlaybackStateCallback(callback: (Int) -> Unit) {
        playbackCallback = callback
    }
}

class FakeMediaBrowserClientFactory : MediaBrowserClientFactory {
    val connections = mutableMapOf<String, FakeMediaBrowserConnection>()

    fun getOrCreate(packageName: String): FakeMediaBrowserConnection {
        return connections.getOrPut(packageName) { FakeMediaBrowserConnection() }
    }

    override fun createConnection(packageName: String, serviceClassName: String?): MediaBrowserConnection {
        return getOrCreate(packageName)
    }
}

class FakeAudioKeyDispatcher : AudioKeyDispatcher {
    var dispatchedPlayKey = false
    var lastActivePackage: String? = null

    override fun dispatchPlayKey(): Boolean {
        dispatchedPlayKey = true
        return true
    }

    override fun getLastActiveMediaPackage(): String? = lastActivePackage
}

class FakeAppOpenerSeam : AppOpenerSeam {
    var result: OpenRequestResult = OpenRequestResult.REQUESTED
    var lastOpenedPackage: String? = null
    var openCount = 0

    override fun openApp(packageName: String): OpenRequestResult {
        openCount++
        lastOpenedPackage = packageName
        return result
    }
}

class FakeMediaSessionSource : MediaSessionSource {
    private val _activeSession = MutableStateFlow<MediaSessionSnapshot?>(null)
    override val activeSession: StateFlow<MediaSessionSnapshot?> = _activeSession.asStateFlow()

    var startCount = 0
        private set
    var stopCount = 0
        private set
    var playCount = 0
        private set
    var pauseCount = 0
        private set
    var skipNextCount = 0
        private set
    var skipPrevCount = 0
        private set
    var preferredPackage: String? = null
        private set

    fun emit(snapshot: MediaSessionSnapshot?) {
        _activeSession.value = snapshot
    }

    override fun setPreferredPackage(packageName: String?) {
        preferredPackage = packageName
    }

    override fun start() {
        startCount++
    }

    override fun stop() {
        stopCount++
    }

    override fun play() {
        playCount++
    }

    override fun pause() {
        pauseCount++
    }

    override fun skipToNext() {
        skipNextCount++
    }

    override fun skipToPrevious() {
        skipPrevCount++
    }
}
