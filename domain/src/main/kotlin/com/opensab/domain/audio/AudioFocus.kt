package com.opensab.domain.audio

enum class AudioFocusKind {

    /**
     * The music pauses. Asked for a phone call, which is a conversation: ducking a song under a
     * voice the rider has to answer is not enough.
     */
    TRANSIENT,

    /**
     * The music drops to a background level and keeps playing. Asked for a spoken page, which is a
     * short announcement: pausing a track for a nine-second forecast and resuming it afterwards is
     * worse than talking over a quieter one.
     */
    TRANSIENT_MAY_DUCK
}

/**
 * A held audio focus, and the only way to give it back.
 *
 * The lease exists so the focus has **one owner per holder** rather than one owner per process.
 * Two things duck the music — the voice of a spoken page and a live call — and they overlap: a call
 * arrives mid-forecast. If both spoke to a single shared object, whichever finished first would
 * drop the other's focus. Each takes its own lease and gives back only its own.
 */
fun interface AudioFocusLease {

    /** Idempotent. */
    fun abandon()
}

/** Port over `AudioManager`'s focus, so everything that decides *when* to duck stays pure JVM. */
interface AudioFocus {

    /**
     * `null` when denied — not an error, and nothing retries: the voice still speaks and the call
     * still rings, only over the music instead of above it.
     */
    fun request(kind: AudioFocusKind): AudioFocusLease?
}

object NoAudioFocus : AudioFocus {
    override fun request(kind: AudioFocusKind): AudioFocusLease? = null
}
