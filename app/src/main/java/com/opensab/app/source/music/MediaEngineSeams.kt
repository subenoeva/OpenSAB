@file:Suppress("DEPRECATION")

package com.opensab.app.source.music

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import com.opensab.logging.Log

interface WakeLockSeam {
    val isHeld: Boolean
    fun acquire(timeoutMs: Long)
    fun release()
}

class AndroidWakeLockSeam(context: Context) : WakeLockSeam {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val wakeLock: PowerManager.WakeLock? = powerManager?.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "OpenSAB:HeadlessMediaWakeLock"
    )?.apply {
        setReferenceCounted(false) // Allows extending timeout without nested lock counts
    }

    override val isHeld: Boolean
        get() = wakeLock?.isHeld == true

    override fun acquire(timeoutMs: Long) {
        try {
            wakeLock?.acquire(timeoutMs)
            Log.d("WakeLock acquired for ${timeoutMs}ms")
        } catch (t: Throwable) {
            Log.w(t, "Error acquiring WakeLock")
        }
    }

    override fun release() {
        try {
            // Safe release against WakeLock under-locked
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
                Log.d("WakeLock released deterministically")
            }
        } catch (e: RuntimeException) {
            Log.w(e, "Safe release: WakeLock was not held or already expired")
        }
    }
}

data class MediaBrowserItem(
    val mediaId: String,
    val isPlayable: Boolean,
    val isBrowsable: Boolean
)

data class MediaServiceComponent(
    val packageName: String,
    val className: String
) {
    fun toComponentName(): ComponentName = ComponentName(packageName, className)
}

enum class OpenRequestResult { REQUESTED, UNAVAILABLE, REJECTED }

interface AppOpenerSeam {
    fun openApp(packageName: String): OpenRequestResult
}

class AndroidAppOpenerSeam(
    private val context: Context? = null,
    private val isKeyguardLockedProvider: () -> Boolean = {
        (context?.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked == true
    },
    private val canDrawOverlaysProvider: () -> Boolean = {
        context?.let { android.provider.Settings.canDrawOverlays(it) } ?: false
    },
    private val launchIntentProvider: (String) -> Any? = { pkg ->
        context?.packageManager?.getLaunchIntentForPackage(pkg)
    },
    private val activityStarter: (Any) -> Unit = { intent ->
        if (intent is android.content.Intent) {
            context?.startActivity(intent)
        }
    }
) : AppOpenerSeam {
    override fun openApp(packageName: String): OpenRequestResult {
        val intent = launchIntentProvider(packageName)
            ?: return OpenRequestResult.UNAVAILABLE
        if (intent is android.content.Intent) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            val hasOverlay = canDrawOverlaysProvider()
            val isLocked = isKeyguardLockedProvider()
            if (hasOverlay) {
                Log.i("AndroidAppOpenerSeam: SYSTEM_ALERT_WINDOW granted; BAL exemption applies for opening $packageName from background/lockscreen")
            } else {
                Log.w("AndroidAppOpenerSeam: SYSTEM_ALERT_WINDOW not granted; BAL restriction may block background launch of $packageName if device is locked")
            }

            if (!hasOverlay && isLocked) {
                Log.w("AndroidAppOpenerSeam: BAL would block launch of $packageName (no overlay permission and keyguard is locked). Returning REJECTED.")
                return OpenRequestResult.REJECTED
            }

            activityStarter(intent)
            OpenRequestResult.REQUESTED
        } catch (_: android.content.ActivityNotFoundException) {
            OpenRequestResult.UNAVAILABLE
        } catch (_: SecurityException) {
            OpenRequestResult.REJECTED
        } catch (_: Throwable) {
            OpenRequestResult.REJECTED
        }
    }
}

data class BrowserReady(
    val rootId: String,
    val initialState: Int? = null,
    val actions: Long = 0L
)

interface MediaBrowserConnection {
    val rootId: String
        get() = "root"
    fun connect(callback: MediaBrowserConnectionCallback)
    fun disconnect()
    fun subscribe(parentId: String, callback: MediaBrowserSubscriptionCallback)
    fun unsubscribe(parentId: String)
    fun play()
    fun playFromMediaId(mediaId: String)
    fun registerPlaybackStateCallback(callback: (Int) -> Unit)
}

interface MediaBrowserConnectionCallback {
    fun onConnected() {}
    fun onConnected(ready: BrowserReady) {
        onConnected()
    }
    fun onConnectionFailed()
    fun onConnectionSuspended()
}

interface MediaBrowserSubscriptionCallback {
    fun onChildrenLoaded(parentId: String, children: List<MediaBrowserItem>)
    fun onError(parentId: String)
}

interface MediaBrowserClientFactory {
    fun createConnection(packageName: String, serviceClassName: String?): MediaBrowserConnection
}

/**
 * Opens a real `MediaBrowserCompat` connection unless [override] hands one back first. Release
 * binds [NoMediaBrowserConnectionOverride]; the debug build binds its simulator.
 */
class AndroidMediaBrowserClientFactory(
    private val context: Context,
    private val override: MediaBrowserConnectionOverride
) : MediaBrowserClientFactory {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun createConnection(packageName: String, serviceClassName: String?): MediaBrowserConnection =
        override.connectionFor(packageName)
            ?: AndroidMediaBrowserConnection(context, packageName, serviceClassName, mainHandler)
}

private class AndroidMediaBrowserConnection(
    private val context: Context,
    private val packageName: String,
    private val serviceClassName: String?,
    private val handler: Handler
) : MediaBrowserConnection {

    override val rootId: String
        get() = browser?.root ?: "root"

    private var browser: MediaBrowserCompat? = null
    private var controller: MediaControllerCompat? = null
    private var stateCallback: ((Int) -> Unit)? = null

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            state?.state?.let { stateCallback?.invoke(it) }
        }
    }

    private fun resolveComponentFromPm(pkg: String): MediaServiceComponent? {
        return try {
            val intent = android.content.Intent("android.media.browse.MediaBrowserService").setPackage(pkg)
            val services = context.packageManager.queryIntentServices(intent, 0)
            val serviceInfo = services.firstOrNull()?.serviceInfo
            if (serviceInfo != null) {
                MediaServiceComponent(serviceInfo.packageName, serviceInfo.name)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun connect(callback: MediaBrowserConnectionCallback) {
        handler.post {
            val component = if (!serviceClassName.isNullOrBlank()) {
                ComponentName(packageName, serviceClassName)
            } else {
                MediaAppScannerDefaults.resolveBrowserComponent(
                    packageName = packageName,
                    fromPackageManager = resolveComponentFromPm(packageName)
                )?.toComponentName()
            }

            if (component == null) {
                Log.w("No MediaBrowserService component found for $packageName")
                callback.onConnectionFailed()
                return@post
            }

            val rootHints = Bundle().apply {
                putBoolean("android.service.media.extra.SUGGESTIONS", true)
                putBoolean(android.service.media.MediaBrowserService.BrowserRoot.EXTRA_RECENT, true)
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            }

            val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    try {
                        val token = browser?.sessionToken
                        if (token != null) {
                            controller = MediaControllerCompat(context, token).apply {
                                registerCallback(controllerCallback, handler)
                            }
                        }
                        val state = controller?.playbackState
                        callback.onConnected(
                            BrowserReady(
                                rootId = browser?.root ?: "root",
                                initialState = state?.state,
                                actions = state?.actions ?: 0L
                            )
                        )
                    } catch (t: Throwable) {
                        Log.w(t, "Failed to initialize MediaControllerCompat")
                        callback.onConnectionFailed()
                    }
                }

                override fun onConnectionFailed() = callback.onConnectionFailed()
                override fun onConnectionSuspended() = callback.onConnectionSuspended()
            }

            try {
                browser = MediaBrowserCompat(context, component, connectionCallback, rootHints).apply {
                    connect()
                }
            } catch (t: Throwable) {
                Log.w(t, "Failed to create or connect MediaBrowserCompat for $packageName")
                callback.onConnectionFailed()
            }
        }
    }

    override fun disconnect() {
        handler.post {
            try {
                controller?.unregisterCallback(controllerCallback)
                browser?.disconnect()
            } catch (t: Throwable) {
                Log.w(t, "Error disconnecting MediaBrowser")
            } finally {
                browser = null
                controller = null
            }
        }
    }

    override fun subscribe(parentId: String, callback: MediaBrowserSubscriptionCallback) {
        handler.post {
            browser?.subscribe(parentId, object : MediaBrowserCompat.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
                    val mapped = children.map {
                        MediaBrowserItem(
                            mediaId = it.mediaId.orEmpty(),
                            isPlayable = it.isPlayable,
                            isBrowsable = it.isBrowsable
                        )
                    }
                    callback.onChildrenLoaded(parentId, mapped)
                }

                override fun onError(parentId: String) = callback.onError(parentId)
            })
        }
    }

    override fun unsubscribe(parentId: String) {
        handler.post {
            try {
                browser?.unsubscribe(parentId)
            } catch (_: Throwable) {}
        }
    }

    override fun play() {
        handler.post { controller?.transportControls?.play() }
    }

    override fun playFromMediaId(mediaId: String) {
        handler.post { controller?.transportControls?.playFromMediaId(mediaId, null) }
    }

    override fun registerPlaybackStateCallback(callback: (Int) -> Unit) {
        stateCallback = callback
    }
}

interface AudioKeyDispatcher {
    fun dispatchPlayKey(): Boolean
    fun getLastActiveMediaPackage(): String?
}

class AndroidAudioKeyDispatcher(private val context: Context) : AudioKeyDispatcher {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    override fun dispatchPlayKey(): Boolean {
        val am = audioManager ?: return false
        // Complete key event pair: ACTION_DOWN followed by ACTION_UP
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
        am.dispatchMediaKeyEvent(down)
        am.dispatchMediaKeyEvent(up)
        return true
    }

    override fun getLastActiveMediaPackage(): String? {
        // Safe query: runCatching guards against SecurityException if notification access is denied
        return runCatching {
            val listener = ComponentName(context, OpenSabNotificationListenerService::class.java)
            sessionManager?.getActiveSessions(listener)?.firstOrNull()?.packageName
        }.getOrNull()
    }
}

object MediaAppScannerDefaults {
    val KNOWN_PLAYERS = mapOf(
        "com.spotify.music" to "com.spotify.music.service.SpotifyMediaBrowserServiceCompat",
        "app.revanced.android.apps.spotify" to "com.spotify.music.service.SpotifyMediaBrowserServiceCompat",
        "app.morphe.android.apps.spotify" to "com.spotify.music.service.SpotifyMediaBrowserServiceCompat",
        "com.google.android.apps.youtube.music" to "com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService",
        "app.revanced.android.apps.youtube.music" to "com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService",
        "app.morphe.android.apps.youtube.music" to "com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService",
        "org.videolan.vlc" to "org.videolan.vlc.media.MediaBrowserService",
        "de.danoeh.antennapod" to "de.danoeh.antennapod.core.service.playback.PlaybackService",
        "au.com.shiftyjelly.pocketcasts" to "au.com.shiftyjelly.pocketcasts.services.playback.PlaybackService",
        "com.maxmpz.audioplayer" to "com.maxmpz.audioplayer.player.PlayerService",
        "in.krosbits.musicolet" to "in.krosbits.musicolet.services.MediaBrowserService",
        "tunein.player" to "tunein.player.service.TuneInMediaBrowserService"
    )

    fun resolveServiceComponent(packageName: String): MediaServiceComponent? {
        val serviceClass = KNOWN_PLAYERS[packageName]
        return if (serviceClass != null) MediaServiceComponent(packageName, serviceClass) else null
    }

    /**
     * The component to bind for [packageName], preferring what the system reports right now.
     *
     * [KNOWN_PLAYERS] is a fallback and not the first choice. A player is free to move its service
     * between releases — Spotify moved its out of `com.spotify.music.service`, and the stale entry
     * winning over the package manager turned every browse into
     * `Unable to start service ... not found`, which the engine could only recover from by opening
     * the app on screen. Preferring the live answer also means the next rename fixes itself.
     *
     * The table still earns its place where [fromPackageManager] is null: package visibility can
     * hide a service the app is nonetheless allowed to bind to.
     */
    fun resolveBrowserComponent(
        packageName: String,
        fromPackageManager: MediaServiceComponent?
    ): MediaServiceComponent? = fromPackageManager ?: resolveServiceComponent(packageName)
}
