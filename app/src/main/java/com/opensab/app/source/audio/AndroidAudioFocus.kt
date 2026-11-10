package com.opensab.app.source.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.opensab.domain.audio.AudioFocus
import com.opensab.domain.audio.AudioFocusKind
import com.opensab.domain.audio.AudioFocusLease
import com.opensab.logging.Log

/**
 * The platform half of [AudioFocus].
 *
 * One `AudioFocusRequest` per lease rather than one per process, which is what lets the voice and a
 * live call hold their own at the same time — a call arriving mid-forecast — and give back only
 * their own. `AudioManager` tracks them separately as long as each is abandoned with the very
 * object it was requested with.
 */
class AndroidAudioFocus(private val audioManager: AudioManager) : AudioFocus {

    override fun request(kind: AudioFocusKind): AudioFocusLease? {
        val listener = object : AudioManager.OnAudioFocusChangeListener {
            override fun onAudioFocusChange(focusChange: Int) = Unit
        }
        val request = AudioFocusRequest.Builder(kind.gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(kind.usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(listener)
            .build()

        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Not an error and nothing retries: the voice still speaks and the call still rings,
            // over the music instead of above it. Logged because it looks exactly like focus
            // handling being broken.
            Log.w("Audio focus denied for %s: the music will not get out of the way", kind)
            return null
        }

        return AudioFocusLease { audioManager.abandonAudioFocusRequest(request) }
    }

    private val AudioFocusKind.gain: Int
        get() = when (this) {
            AudioFocusKind.TRANSIENT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioFocusKind.TRANSIENT_MAY_DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }

    /**
     * What the focus is *for*, which is what tells the system how to treat it. A spoken page is
     * guidance — the same class of sound as a navigation instruction, and what a media app ducks
     * for — and a call is a conversation.
     */
    private val AudioFocusKind.usage: Int
        get() = when (this) {
            AudioFocusKind.TRANSIENT -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            AudioFocusKind.TRANSIENT_MAY_DUCK ->
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        }
}
