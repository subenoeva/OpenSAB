package com.opensab.app.source.music

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.opensab.domain.music.LiveMediaSession
import com.opensab.domain.music.MediaSessionSnapshot
import com.opensab.domain.music.MediaSessionSource
import com.opensab.domain.music.TrackMetadata
import com.opensab.domain.music.isSameSession
import com.opensab.domain.music.playbackStatusOf
import com.opensab.domain.music.selectLiveSession
import com.opensab.logging.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [MediaSessionSource] over the real Android media session stack.
 *
 * Reading other apps' sessions is gated behind an enabled [OpenSabNotificationListenerService]:
 * without it every call to `getActiveSessions` throws `SecurityException`. That failure is
 * expected — the user may simply not have granted media access yet — so it degrades to "no active
 * session" and never propagates to the motorcycle session.
 */
class MediaSessionManagerSource(
    context: Context,
    private val listenerComponent: ComponentName = ComponentName(
        context.applicationContext,
        OpenSabNotificationListenerService::class.java
    )
) : MediaSessionSource {

    private val appContext = context.applicationContext

    private val sessionManager: MediaSessionManager? =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private val _activeSession = MutableStateFlow<MediaSessionSnapshot?>(null)
    override val activeSession: StateFlow<MediaSessionSnapshot?> = _activeSession.asStateFlow()

    /** Framework callbacks are delivered on this looper, never on the BLE coroutine dispatchers. */
    private val handler = Handler(Looper.getMainLooper())

    private var controller: MediaController? = null
    @Volatile
    private var preferredPackage: String? = null

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            logControllers("OnActiveSessionsChanged", controllers)
            bindTo(selectController(controllers))
        }

    private val controllerCallback = object : MediaController.Callback() {

        override fun onMetadataChanged(metadata: MediaMetadata?) = publishSnapshot()

        override fun onPlaybackStateChanged(state: PlaybackState?) = publishSnapshot()

        override fun onSessionDestroyed() {
            refreshControllers()
        }
    }

    private var clientCount = 0
    private var isListenerRegistered = false

    override fun setPreferredPackage(packageName: String?) {
        Log.d("MediaSessionManagerSource: setPreferredPackage=$packageName")
        preferredPackage = packageName
        refreshControllers()
    }

    override fun start() {
        synchronized(this) {
            clientCount++
        }
        val manager = sessionManager
        if (manager == null) {
            Log.w("MediaSessionManager unavailable on this device; music projection disabled")
            return
        }

        runCatching {
            synchronized(this) {
                if (!isListenerRegistered) {
                    manager.addOnActiveSessionsChangedListener(
                        sessionsChangedListener,
                        listenerComponent,
                        handler
                    )
                    isListenerRegistered = true
                }
            }
            refreshControllers()
        }.onFailure { cause ->
            Log.w(cause, "No media access granted: notification listener is not enabled")
            _activeSession.value = null
        }
    }

    private fun refreshControllers() {
        val manager = sessionManager ?: return
        runCatching {
            val controllers = manager.getActiveSessions(listenerComponent)
            logControllers("refreshControllers", controllers)
            bindTo(selectController(controllers))
        }.onFailure { cause ->
            Log.w(cause, "Failed to refresh active sessions")
            bindTo(null)
        }
    }

    private fun logControllers(tag: String, controllers: List<MediaController>?) {
        val summary = controllers?.map {
            "${it.packageName}(state=${it.playbackState?.state})"
        } ?: emptyList()
        Log.i("MediaSessionManagerSource [$tag]: count=${controllers?.size ?: 0}, preferred=$preferredPackage, sessions=$summary")
    }

    private fun selectController(controllers: List<MediaController>?): MediaController? {
        val live = controllers.orEmpty()
        val index = selectLiveSession(live.map { it.toLiveSession() }, preferredPackage) ?: return null
        val chosen = live[index]
        Log.d("selectController: chose ${chosen.packageName} (preferred=$preferredPackage)")
        return chosen
    }

    override fun stop() {
        synchronized(this) {
            clientCount = (clientCount - 1).coerceAtLeast(0)
            if (clientCount > 0) {
                refreshControllers()
                return
            }
            isListenerRegistered = false
        }
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        bindTo(null)
    }

    override fun play() {
        controller?.transportControls?.play()
    }

    override fun pause() {
        controller?.transportControls?.pause()
    }

    override fun skipToNext() {
        controller?.transportControls?.skipToNext()
    }

    override fun skipToPrevious() {
        controller?.transportControls?.skipToPrevious()
    }

    private fun MediaController.toLiveSession() = LiveMediaSession(
        packageName = packageName,
        state = playbackState?.state,
        token = sessionToken
    )

    private fun bindTo(next: MediaController?) {
        if (isSameSession(controller?.toLiveSession(), next?.toLiveSession())) {
            publishSnapshot()
            return
        }

        controller?.unregisterCallback(controllerCallback)
        controller = next
        next?.registerCallback(controllerCallback, handler)
        Log.i("Active media session: ${next?.packageName ?: "none"}")
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val active = controller
        if (active == null) {
            _activeSession.value = null
            return
        }

        val metadata = active.metadata
        _activeSession.value = MediaSessionSnapshot(
            packageName = active.packageName,
            metadata = TrackMetadata(
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            ),
            status = playbackStatusOf(active.playbackState?.state ?: PLAYBACK_STATE_NONE)
        )
    }

    private companion object {
        /** Mirrors `PlaybackState.STATE_NONE`; see the note in MediaMappers.kt. */
        const val PLAYBACK_STATE_NONE = 0
    }
}
