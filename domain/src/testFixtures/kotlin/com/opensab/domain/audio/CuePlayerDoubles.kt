package com.opensab.domain.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Records the cues a test asked for, in order.
 *
 * **Owner: the test that constructs it.**
 *
 * [played] keeps the **order**, which is half of what there is to assert: what matters about the
 * listen beep is not that it happened but that it happened *before* the microphone opened.
 */
class MockCuePlayer : CuePlayer {

    private val _played = mutableListOf<Cue>()

    val played: List<Cue> get() = _played.toList()

    val totalCalls: Int get() = _played.size

    /** Runs on every cue, so a test can record what the world looked like at that instant. */
    var onPlay: ((Cue) -> Unit)? = null

    fun reset() = _played.clear()

    override fun play(cue: Cue) {
        _played += cue
        onPlay?.invoke(cue)
    }
}

/**
 * A headset the test plugs in and out by hand.
 *
 * **Owner: the test that constructs it.**
 */
class FakeHeadsetPresence(connected: Boolean) : HeadsetPresence {

    val state = MutableStateFlow(connected)

    override fun connected(): Flow<Boolean> = state
}
