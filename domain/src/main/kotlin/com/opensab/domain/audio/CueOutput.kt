package com.opensab.domain.audio

/**
 * What the platform says about where a cue would come out, read at the instant of the cue so the
 * log of a link cue can tell "did not sound" apart from "sounded and nobody heard".
 *
 * A description and not a verdict: whether Do Not Disturb or a zero volume actually silences the
 * track is decided below `AudioTrack`, where no JVM test reaches. `AndroidCuePlayer` (`:app`) answers it.
 */
fun interface CueOutput {
    fun describe(): String

    /**
     * The stream the next cue takes is at volume 0 or muted. That is the rider's choice, never
     * raised by the app: [GatedCuePlayer] skips a link cue on it and logs why.
     */
    fun isSilenced(): Boolean = false
}

object UnknownCueOutput : CueOutput {
    override fun describe() = "an unknown output"
}
