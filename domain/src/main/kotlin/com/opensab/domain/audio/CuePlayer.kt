package com.opensab.domain.audio

/**
 * It does not suspend, and that is the contract: a cue is fire-and-forget, and suspending would put
 * a cancellation point between the tone and the thing it announces.
 *
 * **Nobody calls this but the engine and the orchestrator.** A page declares a `Flow<Cue>`; it does
 * not play anything.
 */
interface CuePlayer {

    /** Never throws — the containment lives in [GatedCuePlayer]. */
    fun play(cue: Cue)
}

object NoCuePlayer : CuePlayer {
    override fun play(cue: Cue) = Unit
}

