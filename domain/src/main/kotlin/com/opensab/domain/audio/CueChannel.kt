package com.opensab.domain.audio

/**
 * Which stream a cue plays on. The notification stream keeps cues off the music volume the
 * handlebar controls, but silent mode and Do Not Disturb mute it, and with a headset on that left
 * the rider hearing nothing in the helmet. With a headset connected the cue rides the media channel,
 * at the cost of following the music volume.
 */
enum class CueChannel {
    NOTIFICATION,
    MEDIA;

    companion object {
        fun forRoute(headsetConnected: Boolean): CueChannel =
            if (headsetConnected) MEDIA else NOTIFICATION
    }
}
