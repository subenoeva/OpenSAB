package com.opensab.domain.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import com.opensab.logging.Log

interface MusicManager {

    val currentState: StateFlow<MusicState>

    /**
     * The MUSIC and MUTE indicators follow [currentState] on the cluster side (`MusicIndicators`).
     *
     * It takes no `InputDispatcher`: the handlebar reaches music through `MusicPageProvider.onInput`,
     * like every other page.
     */
    fun start(scope: CoroutineScope)

    fun stop()

    fun play()
    fun pause()
    fun togglePlayPause()
    fun skipToNext()
    fun skipToPrevious()
    fun volumeUp()
    fun volumeDown()
    fun toggleMute()
    fun reset()
}

class DefaultMusicManager(
    private val mediaAdapter: MediaControllerAdapter
) : MusicManager {

    override val currentState: StateFlow<MusicState> = mediaAdapter.musicState

    override fun play() = mediaAdapter.play()
    override fun pause() = mediaAdapter.pause()
    override fun togglePlayPause() = mediaAdapter.togglePlayPause()
    override fun skipToNext() = mediaAdapter.skipToNext()
    override fun skipToPrevious() = mediaAdapter.skipToPrevious()
    override fun volumeUp() = mediaAdapter.volumeUp()
    override fun volumeDown() = mediaAdapter.volumeDown()
    override fun toggleMute() = mediaAdapter.toggleMute()
    override fun reset() = mediaAdapter.reset()

    override fun start(scope: CoroutineScope) {
        Log.i("Starting MusicManager service")

        mediaAdapter.start(scope)
    }

    override fun stop() {
        mediaAdapter.stop()
    }
}
