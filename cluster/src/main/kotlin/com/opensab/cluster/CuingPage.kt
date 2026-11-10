package com.opensab.cluster

import com.opensab.domain.audio.Cue
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A page that says what just happened, while it is mounted: the engine owns the lifetime, the page
 * owns the meaning.
 *
 * A page never plays anything. It declares, and [BaseStateManager] is the only thing in this module
 * that touches a player — which is what makes "one cue per transition, and only one" structure
 * instead of discipline spread over ten providers.
 *
 * A page with nothing to announce does not declare this. Declaring it and emitting nothing is the
 * same as not declaring it, only harder to read.
 */
interface CuingPage : PageProvider {

    /**
     * The cues this page wants heard, one per thing that happened.
     *
     * **Deliberately without replay, and more strictly than [SpeakingPage.speech].** A cue is only
     * true in the instant the thing it announces happens: replaying an `ENTER` on the way back from
     * an interruption would announce a movement nobody made. A voice re-read on resume is useful; a
     * beep re-heard on resume is a lie.
     */
    val cues: Flow<Cue>
}

/**
 * The emitter the pages share, so none of them picks its own buffer.
 *
 * Hot and pushed into, not derived from state: the carousel keeps its cursor in a plain `var` and
 * has no flow to derive from, and `MOVE` versus `EDGE` is not a function of the state but of whether
 * the state *changed* — a cursor that hit the end writes the index it already had.
 *
 * `replay = 0` so nothing is heard twice, and `DROP_OLDEST` so `tryEmit` never fails and never
 * blocks: it is called from `onInput`, which does not suspend.
 */
internal fun cueEmitter(): MutableSharedFlow<Cue> = MutableSharedFlow(
    replay = 0,
    extraBufferCapacity = 8,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
