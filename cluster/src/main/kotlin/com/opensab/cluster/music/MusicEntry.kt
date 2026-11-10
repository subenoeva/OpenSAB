package com.opensab.cluster.music

import com.opensab.cluster.PageProvider
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus

/**
 * Only an actually playing session opens the live [MusicPageProvider]. A cold start, a paused
 * session or a zombie media session with cached metadata goes through [MusicLoadingPageProvider],
 * which gives the cluster immediate feedback and starts playback.
 */
internal fun musicEntryPage(
    state: MusicState,
    livePage: () -> PageProvider,
    loadingPage: () -> PageProvider
): PageProvider {
    val isWarm = state.status == PlaybackStatus.PLAYING
    return if (isWarm) livePage() else loadingPage()
}
