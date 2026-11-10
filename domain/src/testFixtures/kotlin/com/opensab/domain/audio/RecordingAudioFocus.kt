package com.opensab.domain.audio

/**
 * Records what was asked for and when it was given back, which is the whole of what the two owners
 * of a focus — the voice of a spoken page and a live call — promise: take it for exactly as long as
 * the thing lasts, and take one each so neither drops the other's.
 */
class RecordingAudioFocus(
    /** When false, every request is denied, the way a phone that will not yield the focus does. */
    private val granted: Boolean = true
) : AudioFocus {

    /** Every kind asked for, in order, whether granted or not. */
    val requests = mutableListOf<AudioFocusKind>()

    /** Every kind given back, in the order the leases were abandoned. */
    val abandoned = mutableListOf<AudioFocusKind>()

    /** Kinds asked for and not yet given back. */
    val held: List<AudioFocusKind>
        get() = if (!granted) emptyList() else requests.toMutableList().apply {
            abandoned.forEach { remove(it) }
        }

    override fun request(kind: AudioFocusKind): AudioFocusLease? {
        requests += kind
        if (!granted) return null

        // A lease that only counts its first abandon, because the platform half does the same:
        // `abandonAudioFocusRequest` on a request already dropped is a no-op, and a `finally` can
        // run after an explicit release.
        var released = false
        return AudioFocusLease {
            if (!released) {
                released = true
                abandoned += kind
            }
        }
    }
}
