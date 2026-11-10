package com.opensab.cluster.music

import com.opensab.cluster.LiveScreenPresets
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.mirror.MIRROR_REQUEST_ID
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.mirrorPageOf
import com.opensab.cluster.redrawOn
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
import kotlinx.coroutines.flow.flow

/** Live media card pushed when the rider selects the music entry of the root menu. */
class MusicPageProvider(
    private val musicState: StateFlow<MusicState>,
    /** No default: one would be a page painting English because the graph forgot. */
    private val copy: ClusterTextResolver,
    /** "Handlebar Volume Control". Off, the vertical keys stop changing the phone volume here; mute does not. */
    private val volumeControl: StateFlow<Boolean>,
    private val musicManager: MusicManager? = null
) : PageProvider, SpeakingPage {

    override val name: String = "MusicPage"

    override val speech: Flow<List<SpeechRequest>> = flow {
        emit(listOf(SpeechRequest(SpeechToken.MUSIC_LIVE)))
    }

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN

    override fun render(requestId: Int): ProjectionFrame {
        val state = musicState.value
        return LiveScreenPresets.music(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MUSIC),
            title = state.metadata.title,
            artist = state.metadata.artist,
            playing = state.status == PlaybackStatus.PLAYING
        )
    }

    /**
     * The page where the labels have to carry the semantics, because the button names do not: the
     * layout puts Play/Pause on `BACK` and the next track on `SELECT`. Every zone below states the
     * effect its press has, not the button that reaches it.
     *
     * `PREVIOUS` and `VOLUME_UP` land on the same `volumeUp()`, as do `NEXT` and `VOLUME_DOWN` on
     * `volumeDown()`; the dedicated ids travel as aliases so no button is unreachable from the app
     * without the card painting two identical zones. `MENU` takes the header because `VOLUME_MUTE`
     * already owns `LEFT`.
     */
    override fun mirror(): MirrorPage {
        val playPauseToken = if (musicState.value.status == PlaybackStatus.PLAYING) {
            MirrorActionToken.PAUSE
        } else {
            MirrorActionToken.PLAY
        }
        return mirrorPageOf(
            name,
            render(MIRROR_REQUEST_ID),
            actions = listOf(
                MirrorAction(playPauseToken, FunctionId.BACK, MirrorSlot.PRIMARY),
                MirrorAction(MirrorActionToken.NEXT_TRACK, FunctionId.SELECT, MirrorSlot.RIGHT),
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
                    MirrorActionToken.VOLUME_MUTE,
                    FunctionId.VOLUME_MUTE,
                    MirrorSlot.LEFT
                ),
                MirrorAction(
                    MirrorActionToken.OPEN_ROOT_MENU,
                    FunctionId.MENU,
                    MirrorSlot.HEADER
                )
            )
        )
    }

    override val invalidations: Flow<Unit> = redrawOn(musicState)

    override fun onInput(functionId: FunctionId): NavigationAction = when (functionId) {
        FunctionId.MENU -> NavigationAction.Reset

        FunctionId.BACK -> {
            musicManager?.togglePlayPause()
            NavigationAction.None
        }

        FunctionId.SELECT -> {
            musicManager?.skipToNext()
            NavigationAction.None
        }

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
}
