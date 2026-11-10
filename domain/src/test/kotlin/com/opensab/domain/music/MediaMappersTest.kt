package com.opensab.domain.music

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaMappersTest {

    @Test
    fun mapsFrameworkPlaybackStatesToDomainStatus() {
        assertEquals(PlaybackStatus.PLAYING, playbackStatusOf(3))  // STATE_PLAYING
        assertEquals(PlaybackStatus.PLAYING, playbackStatusOf(6))  // STATE_BUFFERING
        assertEquals(PlaybackStatus.PAUSED, playbackStatusOf(2))   // STATE_PAUSED
        assertEquals(PlaybackStatus.STOPPED, playbackStatusOf(1))  // STATE_STOPPED
        assertEquals(PlaybackStatus.NONE, playbackStatusOf(0))     // STATE_NONE
    }

    @Test
    fun mapsUnknownPlaybackStateToNone() {
        assertEquals(PlaybackStatus.NONE, playbackStatusOf(99))
        assertEquals(PlaybackStatus.NONE, playbackStatusOf(-1))
    }

    @Test
    fun convertsVolumeIndexToPercent() {
        assertEquals(0, volumePercentOf(index = 0, maxIndex = 15))
        assertEquals(100, volumePercentOf(index = 15, maxIndex = 15))
        assertEquals(47, volumePercentOf(index = 7, maxIndex = 15))
    }

    @Test
    fun treatsZeroMaxVolumeAsSilent() {
        assertEquals(0, volumePercentOf(index = 0, maxIndex = 0))
        assertEquals(0, volumePercentOf(index = 5, maxIndex = 0))
    }

    @Test
    fun clampsVolumePercentToTheValidRange() {
        assertEquals(100, volumePercentOf(index = 20, maxIndex = 15))
        assertEquals(0, volumePercentOf(index = -3, maxIndex = 15))
    }
}
