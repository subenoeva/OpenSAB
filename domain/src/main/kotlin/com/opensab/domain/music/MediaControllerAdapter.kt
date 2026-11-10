package com.opensab.domain.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface MediaControllerAdapter {

    val musicState: StateFlow<MusicState>

    /** No-op by default: an adapter that holds no system resource needs no lifecycle. */
    fun start(scope: CoroutineScope) {}

    fun stop() {}

    fun play()
    fun pause()
    fun togglePlayPause()
    fun skipToNext()
    fun skipToPrevious()
    fun volumeUp()
    fun volumeDown()
    fun toggleMute()
    fun reset() {}
}
