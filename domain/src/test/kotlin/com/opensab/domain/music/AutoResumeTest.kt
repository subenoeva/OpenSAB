package com.opensab.domain.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoResumeTest {
    @Test
    fun resumesWhenOnWithAFavoriteAndNothingPlaying() =
        assertTrue(shouldAutoResume(true, "com.spotify.music", PlaybackStatus.PAUSED))

    @Test
    fun staysQuietWhenOff() =
        assertFalse(shouldAutoResume(false, "com.spotify.music", PlaybackStatus.NONE))

    @Test
    fun staysQuietWithoutAFavorite() {
        assertFalse(shouldAutoResume(true, null, PlaybackStatus.NONE))
        assertFalse(shouldAutoResume(true, " ", PlaybackStatus.NONE))
    }

    @Test
    fun staysQuietWhenMusicIsAlreadyPlaying() =
        assertFalse(shouldAutoResume(true, "com.spotify.music", PlaybackStatus.PLAYING))
}
