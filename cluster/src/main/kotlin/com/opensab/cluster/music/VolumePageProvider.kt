package com.opensab.cluster.music

import com.opensab.cluster.CuingPage
import com.opensab.cluster.LiveScreenPresets
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.cueEmitter
import com.opensab.cluster.mirror.MIRROR_REQUEST_ID
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.mirrorPageOf
import com.opensab.cluster.redrawOn
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.ClusterToken
import com.opensab.domain.audio.Cue
import com.opensab.domain.music.VolumeLevel
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechQuantity
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow

/** Live volume control page pushed when the rider selects the volume entry of the root menu. */
class VolumePageProvider(
    private val volumeLevel: StateFlow<VolumeLevel>,
    /** No default: one would be a page painting English because the graph forgot. */
    private val copy: ClusterTextResolver,
    /** "Handlebar Volume Control". Off, the vertical keys stop changing the phone volume here; mute does not. */
    private val volumeControl: StateFlow<Boolean>,
    private val volumeSource: VolumeSource? = null
) : CuingPage, SpeakingPage {

    override val name: String = "VolumePage"

    override val speech: Flow<List<SpeechRequest>> = flow {
        val state = volumeLevel.value
        val request = if (state.isMuted) {
            SpeechRequest(SpeechToken.VOLUME_MUTED)
        } else {
            SpeechRequest(
                SpeechToken.VOLUME_LEVEL,
                SpeechArg.Quantity(SpeechQuantity.PERCENT, state.percent)
            )
        }
        emit(listOf(request))
    }

    private val _cues = cueEmitter()

    /** EDGE and nothing else: the volume is a percentage and not a list, so it has no cursor to MOVE. */
    override val cues: Flow<Cue> = _cues.asSharedFlow()

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN

    override fun render(requestId: Int): ProjectionFrame {
        val state = volumeLevel.value
        return LiveScreenPresets.volume(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_VOLUME),
            mutedText = copy.resolve(ClusterToken.VOLUME_MUTE),
            percent = state.percent,
            isMuted = state.isMuted
        )
    }

    /**
     * The three volume zones each carry an alias because `onInput` routes two `FunctionId`s to the
     * same source call; without the alias the "nothing hidden" invariant would force a second,
     * identical zone for each. Mute is the one that reads backwards at a glance: `SELECT` is the
     * button the zone emits and `VOLUME_MUTE` the alias, matching the branch order in `onInput`.
     */
    override fun mirror(): MirrorPage = mirrorPageOf(
        name,
        render(MIRROR_REQUEST_ID),
        actions = listOf(
            MirrorAction(
                token = MirrorActionToken.VOLUME_UP,
                functionId = FunctionId.PREVIOUS,
                slot = MirrorSlot.UP,
                aliases = setOf(FunctionId.VOLUME_UP)
            ),
            MirrorAction(
                token = MirrorActionToken.VOLUME_DOWN,
                functionId = FunctionId.NEXT,
                slot = MirrorSlot.DOWN,
                aliases = setOf(FunctionId.VOLUME_DOWN)
            ),
            MirrorAction(
                token = MirrorActionToken.VOLUME_MUTE,
                functionId = FunctionId.SELECT,
                slot = MirrorSlot.PRIMARY,
                aliases = setOf(FunctionId.VOLUME_MUTE)
            ),
            MirrorAction(
                token = MirrorActionToken.GO_BACK,
                functionId = FunctionId.BACK,
                slot = MirrorSlot.LEFT
            ),
            MirrorAction(
                token = MirrorActionToken.OPEN_ROOT_MENU,
                functionId = FunctionId.MENU,
                slot = MirrorSlot.HEADER
            )
        )
    )

    override val invalidations: Flow<Unit> = redrawOn(volumeLevel)

    override fun onInput(functionId: FunctionId): NavigationAction = when (functionId) {
        FunctionId.BACK -> NavigationAction.Pop

        FunctionId.MENU -> NavigationAction.Reset

        // The level is read **before** the command, which is how the top is detected at all: the
        // volume has no index, it has a percentage, so hitting the end is asking to raise at 100 or
        // to lower at 0. `AudioManagerVolumeSource` re-reads right after a handlebar command, which
        // is exactly this case.
        FunctionId.PREVIOUS, FunctionId.VOLUME_UP -> {
            if (volumeControl.value) {
                if (volumeLevel.value.percent >= MAX_PERCENT) _cues.tryEmit(Cue.EDGE)
                volumeSource?.raise()
            }
            NavigationAction.None
        }

        FunctionId.NEXT, FunctionId.VOLUME_DOWN -> {
            if (volumeControl.value) {
                if (volumeLevel.value.percent <= MIN_PERCENT) _cues.tryEmit(Cue.EDGE)
                volumeSource?.lower()
            }
            NavigationAction.None
        }

        FunctionId.VOLUME_MUTE, FunctionId.SELECT -> {
            volumeSource?.toggleMute()
            NavigationAction.None
        }

        else -> NavigationAction.None
    }

    private companion object {
        const val MAX_PERCENT = 100
        const val MIN_PERCENT = 0
    }
}
