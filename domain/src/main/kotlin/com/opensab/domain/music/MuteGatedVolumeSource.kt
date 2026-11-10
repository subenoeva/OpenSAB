package com.opensab.domain.music

/**
 * Decorates [VolumeSource] so that [toggleMute] alternates music playback when the rider chooses
 * [isPauseMode], avoiding the loss of navigation speech guidance over STREAM_MUSIC.
 *
 * Single owner of the mute routing logic in the domain layer.
 */
class MuteGatedVolumeSource(
    private val delegate: VolumeSource,
    private val isPauseMode: () -> Boolean,
    private val togglePlayback: () -> Unit
) : VolumeSource by delegate {

    override fun toggleMute() {
        if (isPauseMode()) {
            togglePlayback()
        } else {
            delegate.toggleMute()
        }
    }
}
