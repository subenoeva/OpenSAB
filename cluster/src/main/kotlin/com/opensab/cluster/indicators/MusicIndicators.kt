package com.opensab.cluster.indicators

import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * MUSIC tracks live playback and MUTE tracks both the mute toggle and a fader dragged to zero. Both
 * are set from one collector, MUSIC first.
 */
class MusicIndicators(
    private val state: StateFlow<MusicState>,
    private val publisher: ActiveIndicatorsPublisher
) : IndicatorTranslator {

    override val name: String = "music"

    override fun start(scope: CoroutineScope) {
        scope.launch {
            state.collect { music ->
                publisher.set(ActiveIndicator.MUSIC, music.status == PlaybackStatus.PLAYING)
                publisher.set(ActiveIndicator.MUTE, music.isMuted || music.volumePercent == 0)
            }
        }
    }
}
