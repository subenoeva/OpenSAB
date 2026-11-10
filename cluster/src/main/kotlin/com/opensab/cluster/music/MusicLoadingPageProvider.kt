package com.opensab.cluster.music

import com.opensab.cluster.LiveScreenPresets
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
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
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Mounted during a cold start of music playback: gives the cluster immediate feedback, starts
 * playback and swaps itself for the live music page as soon as audio plays.
 *
 * If the rider aborts it, the `finally` in [work] resets the launcher to unlock pending debounce
 * states.
 */
class MusicLoadingPageProvider(
    private val musicState: StateFlow<MusicState>,
    private val copy: ClusterTextResolver,
    /** "Handlebar Volume Control". Off, the vertical keys stop changing the phone volume here; mute does not. */
    private val volumeControl: StateFlow<Boolean>,
    private val livePageFactory: () -> PageProvider,
    private val errorPageFactory: () -> PageProvider,
    private val musicManager: MusicManager? = null,
    private val timeoutDuration: Duration = 8.seconds
) : WorkingPage, SpeakingPage {

    override val name: String = "MusicLoadingPage"

    override val speech: Flow<List<SpeechRequest>> = flow {
        emit(listOf(SpeechRequest(SpeechToken.MUSIC_LOADING)))
    }

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.DISCARD

    override val invalidations: Flow<Unit> = emptyFlow()

    override fun render(requestId: Int): ProjectionFrame =
        LiveScreenPresets.musicLoading(
            requestId = requestId,
            header = copy.resolve(ClusterToken.LOADING)
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
            if (volumeControl.value) musicManager?.volumeUp()
            NavigationAction.None
        }
        FunctionId.NEXT, FunctionId.VOLUME_DOWN -> {
            if (volumeControl.value) musicManager?.volumeDown()
            NavigationAction.None
        }
        FunctionId.VOLUME_MUTE -> {
            musicManager?.toggleMute()
            NavigationAction.None
        }
        else -> NavigationAction.None
    }

    override suspend fun work(): NavigationAction {
        return try {
            musicManager?.play()
            withTimeoutOrNull(timeoutDuration) {
                musicState.first { state ->
                    state.status == PlaybackStatus.PLAYING
                }
            }?.let {
                NavigationAction.Replace(livePageFactory())
            } ?: NavigationAction.Replace(errorPageFactory())
        } finally {
            if (musicState.value.status != PlaybackStatus.PLAYING) {
                musicManager?.reset()
            }
        }
    }
}
