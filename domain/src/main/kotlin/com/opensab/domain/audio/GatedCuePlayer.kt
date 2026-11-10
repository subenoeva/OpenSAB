package com.opensab.domain.audio

import com.opensab.logging.Log

/**
 * The one place where it is decided whether a cue sounds, and the one place where a cue may fail.
 *
 * **The setting has a single owner even though the emitters are two.** The engine plays the cues of
 * the mounted page and the orchestrator plays the link; neither knows `soundCuesEnabled` exists.
 * They call [play] and this decides, so turning the cues off cannot end up half-done on one path.
 *
 * [isEnabled] is a function and not a `SettingsRepository` so that `source/audio` never has to see
 * `com.opensab.domain.settings`. It is read at the instant of the cue, so a switch flipped mid-ride
 * governs the next beep.
 *
 * And it is where "never throws" lives: the real player, `AndroidCuePlayer` (`:app`), has no JVM
 * test; here it is provable. The `catch` names `RuntimeException` — `AudioTrack` raises
 * `IllegalStateException` and `IllegalArgumentException` — and never `Exception`.
 */
class GatedCuePlayer(
    private val delegate: CuePlayer,
    private val output: CueOutput,
    private val isEnabled: () -> Boolean
) : CuePlayer {

    override fun play(cue: Cue) {
        val isLinkCue = cue == Cue.LINK_UP || cue == Cue.LINK_DOWN
        if (!isEnabled()) {
            if (isLinkCue) Log.i("Link cue %s skipped: %s", cue, "sound cues are off")
            return
        }
        if (isLinkCue && isSilenced()) {
            Log.i("Link cue %s skipped: %s", cue, "the channel is silenced: ${describeOutput()}")
            return
        }
        try {
            delegate.play(cue)
        } catch (e: RuntimeException) {
            Log.w(e, "Could not play the %s cue; carrying on", cue)
            if (isLinkCue) Log.i("Link cue %s skipped: %s", cue, "the player failed")
            return
        }
        // Only the link: page cues fire on every carousel move and would drown the log.
        if (isLinkCue) Log.i("Link cue %s played on %s", cue, describeOutput())
    }

    private fun isSilenced(): Boolean = try {
        output.isSilenced()
    } catch (e: RuntimeException) {
        false
    }

    private fun describeOutput(): String = try {
        output.describe()
    } catch (e: RuntimeException) {
        "an output that could not be described"
    }
}
