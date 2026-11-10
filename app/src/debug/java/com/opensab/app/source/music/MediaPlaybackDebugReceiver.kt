package com.opensab.app.source.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opensab.domain.music.MediaControllerAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@AndroidEntryPoint
class MediaPlaybackDebugReceiver : BroadcastReceiver() {

    @Inject
    lateinit var mediaControllerAdapter: MediaControllerAdapter

    @Inject
    lateinit var favoriteMediaRepository: FavoriteMediaRepository

    @Inject
    lateinit var simulatedMediaBrowser: SimulatedMediaBrowserOverride

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != ACTION_TRIGGER_PLAY &&
            action != ACTION_SET_FAVORITE &&
            action != ACTION_ATTACH &&
            action != ACTION_DETACH
        ) return

        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val holdMs = if (intent.hasExtra(EXTRA_HOLD_MS)) {
            intent.getLongExtra(EXTRA_HOLD_MS, intent.getIntExtra(EXTRA_HOLD_MS, DEFAULT_HOLD_MS.toInt()).toLong())
        } else {
            DEFAULT_HOLD_MS
        }
        Timber.i("MediaPlaybackDebugReceiver: Received $action (pkg=$pkg, holdMs=$holdMs)")

        if (action == ACTION_ATTACH) {
            val pendingResult = goAsync()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            attachedJob?.cancel()
            attachedJob = scope.coroutineContext[kotlinx.coroutines.Job]
            scope.launch {
                try {
                    mediaControllerAdapter.start(this)
                    Timber.i("MediaPlaybackDebugReceiver: MediaControllerAdapter attached permanently until DETACH")
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        if (action == ACTION_DETACH) {
            val pendingResult = goAsync()
            attachedJob?.cancel()
            attachedJob = null
            mediaControllerAdapter.stop()
            Timber.i("MediaPlaybackDebugReceiver: MediaControllerAdapter detached")
            pendingResult.finish()
            return
        }

        val bufMs = if (intent.hasExtra(EXTRA_BUFFERING_MS)) {
            intent.getLongExtra(EXTRA_BUFFERING_MS, intent.getIntExtra(EXTRA_BUFFERING_MS, 0).toLong())
        } else {
            null
        }

        val isPermanentlyAttached = attachedJob?.isActive == true
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            try {
                if (bufMs != null && bufMs > 0) {
                    simulatedMediaBrowser.simulatedBufferingMs.set(bufMs)
                }
                if (!isPermanentlyAttached) {
                    mediaControllerAdapter.start(this)
                }
                if (pkg != null) {
                    favoriteMediaRepository.setFavoritePackage(pkg.ifBlank { null })
                    delay(200.milliseconds)
                }
                if (action == ACTION_TRIGGER_PLAY) {
                    mediaControllerAdapter.play()
                    delay(holdMs.milliseconds)
                }
            } finally {
                simulatedMediaBrowser.simulatedBufferingMs.set(null)
                if (!isPermanentlyAttached) {
                    // The adapter's collectors would outlive the broadcast otherwise.
                    mediaControllerAdapter.stop()
                }
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        const val ACTION_TRIGGER_PLAY = "com.opensab.debug.TRIGGER_PLAY"
        const val ACTION_SET_FAVORITE = "com.opensab.debug.SET_FAVORITE"
        const val ACTION_ATTACH = "com.opensab.debug.ATTACH"
        const val ACTION_DETACH = "com.opensab.debug.DETACH"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_HOLD_MS = "hold_ms"
        const val EXTRA_BUFFERING_MS = "buffering_ms"
        const val DEFAULT_HOLD_MS = 25_000L

        private var attachedJob: kotlinx.coroutines.Job? = null
    }
}
