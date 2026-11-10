package com.opensab.domain.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockMediaControllerAdapterTest {

    @Test
    fun playbackControlsToggleStateCorrectly() {
        val adapter = MockMediaControllerAdapter()
        assertEquals(PlaybackStatus.NONE, adapter.musicState.value.status)

        adapter.setTrack(TrackMetadata("Hotel California", "Eagles"))
        adapter.play()
        assertEquals(PlaybackStatus.PLAYING, adapter.musicState.value.status)

        adapter.pause()
        assertEquals(PlaybackStatus.PAUSED, adapter.musicState.value.status)

        adapter.togglePlayPause()
        assertEquals(PlaybackStatus.PLAYING, adapter.musicState.value.status)
    }

    @Test
    fun volumeControlsAdjustPercentageAndMute() {
        val adapter = MockMediaControllerAdapter()
        assertEquals(100, adapter.musicState.value.volumePercent)
        assertFalse(adapter.musicState.value.isMuted)

        adapter.volumeDown()
        assertEquals(90, adapter.musicState.value.volumePercent)

        adapter.volumeUp()
        assertEquals(100, adapter.musicState.value.volumePercent)

        adapter.toggleMute()
        assertTrue(adapter.musicState.value.isMuted)

        adapter.toggleMute()
        assertFalse(adapter.musicState.value.isMuted)
    }
}
