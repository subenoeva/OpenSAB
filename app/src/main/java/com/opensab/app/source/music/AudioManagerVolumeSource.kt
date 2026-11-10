package com.opensab.app.source.music

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.opensab.domain.music.VolumeLevel
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.music.volumePercentOf
import com.opensab.logging.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [VolumeSource] over `AudioManager`, acting on the music stream.
 *
 * The handlebar is not the only volume input while riding: the intercom's own buttons move
 * `STREAM_MUSIC` through AVRCP absolute volume, and so do the phone's keys. The system persists every
 * stream index in `Settings.System`, so one observer there re-reads the level whoever changed it.
 * This source owns that observer: registered in [start], unregistered in [stop].
 */
class AudioManagerVolumeSource(context: Context) : VolumeSource {

    private val appContext = context.applicationContext

    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _level = MutableStateFlow(VolumeLevel())
    override val level: StateFlow<VolumeLevel> = _level.asStateFlow()

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    private var isObserving = false

    @Synchronized
    override fun start() {
        if (!isObserving) {
            appContext.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)
            isObserving = true
        }
        refresh()
    }

    @Synchronized
    override fun stop() {
        if (!isObserving) return
        appContext.contentResolver.unregisterContentObserver(settingsObserver)
        isObserving = false
    }

    override fun raise() = adjust(AudioManager.ADJUST_RAISE)

    override fun lower() = adjust(AudioManager.ADJUST_LOWER)

    override fun toggleMute() = adjust(AudioManager.ADJUST_TOGGLE_MUTE)

    private fun adjust(direction: Int) {
        val manager = audioManager ?: return
        runCatching {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }.onFailure { cause ->
            Log.w(cause, "Could not adjust the music stream volume")
        }
        refresh()
    }

    private fun refresh() {
        val manager = audioManager ?: return
        _level.value = VolumeLevel(
            percent = volumePercentOf(
                index = manager.getStreamVolume(AudioManager.STREAM_MUSIC),
                maxIndex = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            ),
            isMuted = manager.isStreamMute(AudioManager.STREAM_MUSIC)
        )
    }
}
