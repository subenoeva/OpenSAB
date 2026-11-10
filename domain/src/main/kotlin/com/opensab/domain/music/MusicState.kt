package com.opensab.domain.music

enum class PlaybackStatus {
    PLAYING,
    PAUSED,
    STOPPED,
    NONE
}

data class TrackMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val durationMs: Long = 0L
)

data class MusicState(
    val metadata: TrackMetadata = TrackMetadata(),
    val status: PlaybackStatus = PlaybackStatus.NONE,
    val volumePercent: Int = 100,
    val isMuted: Boolean = false
)
