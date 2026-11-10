package com.opensab.cluster.music

import com.opensab.cluster.LiveScreenPresets
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.WorkingPage
import com.opensab.cluster.mirror.MIRROR_REQUEST_ID
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.mirrorPageOf
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.ClusterToken
import com.opensab.domain.music.MusicManager
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Shown when a cold start times out without a responsive player; pops itself after [autoPopDuration]. */
internal class MusicErrorPageProvider(
    private val copy: ClusterTextResolver,
    /** "Handlebar Volume Control". Off, the vertical keys stop changing the phone volume here; mute does not. */
    private val volumeControl: StateFlow<Boolean>,
    private val volumeSource: VolumeSource? = null,
    private val musicManager: MusicManager? = null,
    private val autoPopDuration: Duration = 5.seconds
) : WorkingPage, SpeakingPage {

    override val name: String = "MusicErrorPage"

    override val speech: Flow<List<SpeechRequest>> = flow {
        emit(listOf(SpeechRequest(SpeechToken.MUSIC_ERROR)))
    }

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.DISCARD

    override val invalidations: Flow<Unit> = emptyFlow()

    override fun render(requestId: Int): ProjectionFrame =
        LiveScreenPresets.musicError(
            requestId = requestId,
            header = copy.resolve(ClusterToken.MUSIC_ERROR)
        )

    override fun mirror(): MirrorPage =
        mirrorPageOf(
            name,
            render(MIRROR_REQUEST_ID),
            actions = listOf(
                MirrorAction(MirrorActionToken.GO_BACK, FunctionId.BACK, MirrorSlot.LEFT),
                MirrorAction(MirrorActionToken.OPEN_ROOT_MENU, FunctionId.MENU, MirrorSlot.HEADER),
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
                MirrorAction(MirrorActionToken.VOLUME_MUTE, FunctionId.VOLUME_MUTE, MirrorSlot.RIGHT)
            )
        )

    override fun onInput(functionId: FunctionId): NavigationAction = when (functionId) {
        FunctionId.BACK -> NavigationAction.Pop
        FunctionId.MENU -> NavigationAction.Reset
        FunctionId.PREVIOUS, FunctionId.VOLUME_UP -> {
            if (volumeControl.value) volumeSource?.raise() ?: musicManager?.volumeUp()
            NavigationAction.None
        }
        FunctionId.NEXT, FunctionId.VOLUME_DOWN -> {
            if (volumeControl.value) volumeSource?.lower() ?: musicManager?.volumeDown()
            NavigationAction.None
        }
        FunctionId.VOLUME_MUTE -> {
            volumeSource?.toggleMute() ?: musicManager?.toggleMute()
            NavigationAction.None
        }
        else -> NavigationAction.None
    }

    override suspend fun work(): NavigationAction {
        delay(autoPopDuration)
        return NavigationAction.Pop
    }
}
