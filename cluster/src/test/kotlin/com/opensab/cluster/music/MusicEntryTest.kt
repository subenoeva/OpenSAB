package com.opensab.cluster.music

import com.opensab.cluster.StubPageProvider
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.TrackMetadata
import com.opensab.sab.protocol.projection.ProjectionFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MusicEntryTest {

    private val livePage = StubPageProvider("LiveMusic", ProjectionFrame.RequestSabMode(1))
    private val loadingPage = StubPageProvider("LoadingMusic", ProjectionFrame.RequestSabMode(2))

    @Test
    fun returnsLoadingPageWhenColdWithStatusNoneAndEmptyMetadata() {
        val state = MusicState(status = PlaybackStatus.NONE, metadata = TrackMetadata())
        val result = musicEntryPage(state, livePage = { livePage }, loadingPage = { loadingPage })
        assertSame(loadingPage, result)
    }

    @Test
    fun returnsLivePageWhenPlaying() {
        val state = MusicState(status = PlaybackStatus.PLAYING, metadata = TrackMetadata())
        val result = musicEntryPage(state, livePage = { livePage }, loadingPage = { loadingPage })
        assertSame(livePage, result)
    }

    @Test
    fun returnsLoadingPageWhenPaused() {
        val state = MusicState(status = PlaybackStatus.PAUSED, metadata = TrackMetadata(title = "Yesterday"))
        val result = musicEntryPage(state, livePage = { livePage }, loadingPage = { loadingPage })
        assertSame(loadingPage, result)
    }

    @Test
    fun returnsLoadingPageWhenMetadataHasNonEmptyTitleButStatusNone() {
        val state = MusicState(status = PlaybackStatus.NONE, metadata = TrackMetadata(title = "Yesterday"))
        val result = musicEntryPage(state, livePage = { livePage }, loadingPage = { loadingPage })
        assertSame(loadingPage, result)
    }

    @Test
    fun buildsOnlyThePageItOpens() {
        var liveBuilt = 0
        var loadingBuilt = 0
        val state = MusicState(status = PlaybackStatus.NONE, metadata = TrackMetadata())

        musicEntryPage(
            state = state,
            livePage = { liveBuilt++; livePage },
            loadingPage = { loadingBuilt++; loadingPage }
        )

        assertEquals(0, liveBuilt)
        assertEquals(1, loadingBuilt)
    }
}
