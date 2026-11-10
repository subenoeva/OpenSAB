package com.opensab.domain.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSessionSelectionTest {

    private val spotify = "com.spotify.music"
    private val antennaPod = "de.danoeh.antennapod"

    @Test
    fun returnsNullWhenThereIsNoSessionAtAll() {
        assertNull(selectSessionIndex(emptyList(), spotify))
    }

    @Test
    fun prefersTheFavouriteWhenItIsTheOnePlaying() {
        val candidates = listOf(
            SessionCandidate(antennaPod, STATE_PAUSED),
            SessionCandidate(spotify, STATE_PLAYING)
        )
        assertEquals(1, selectSessionIndex(candidates, spotify))
    }

    @Test
    fun aSessionThatIsActuallyPlayingBeatsAPausedFavourite() {
        val candidates = listOf(
            SessionCandidate(spotify, STATE_PAUSED),
            SessionCandidate(antennaPod, STATE_PLAYING)
        )
        assertEquals(
            "the rider hears AntennaPod, so the dash and the handlebar must own AntennaPod",
            1,
            selectSessionIndex(candidates, spotify)
        )
    }

    @Test
    fun fallsBackToThePausedFavouriteWhenNothingIsPlaying() {
        val candidates = listOf(
            SessionCandidate(antennaPod, STATE_PAUSED),
            SessionCandidate(spotify, STATE_PAUSED)
        )
        assertEquals(1, selectSessionIndex(candidates, spotify))
    }

    @Test
    fun fallsBackToTheSystemOrderWhenTheFavouriteHasNoSession() {
        val candidates = listOf(
            SessionCandidate(antennaPod, STATE_PAUSED),
            SessionCandidate("com.whatsapp", STATE_STOPPED)
        )
        assertEquals(0, selectSessionIndex(candidates, spotify))
    }

    @Test
    fun withoutAFavouriteThePlayingSessionWins() {
        val candidates = listOf(
            SessionCandidate("com.whatsapp", STATE_PAUSED),
            SessionCandidate(antennaPod, STATE_PLAYING)
        )
        assertEquals(1, selectSessionIndex(candidates, null))
    }

    @Test
    fun aBlankFavouriteIsNoFavourite() {
        val candidates = listOf(
            SessionCandidate("com.whatsapp", STATE_PAUSED),
            SessionCandidate(antennaPod, STATE_PLAYING)
        )
        assertEquals(1, selectSessionIndex(candidates, "  "))
    }

    @Test
    fun ignoresBlacklistedSessionEvenWhenPlaying() {
        val candidates = listOf(
            SessionCandidate("com.stremio.one", STATE_PLAYING),
            SessionCandidate(spotify, STATE_PAUSED)
        )
        assertEquals(
            "Blacklisted app playing should not take over paused music favourite",
            1,
            selectSessionIndex(candidates, spotify)
        )
    }

    @Test
    fun returnsNullWhenAllSessionsAreBlacklisted() {
        val candidates = listOf(
            SessionCandidate("com.stremio.one", STATE_PLAYING),
            SessionCandidate("org.telegram.messenger", STATE_PLAYING)
        )
        assertNull(selectSessionIndex(candidates, null))
    }

    @Test
    fun doesNotFallBackToBlacklistedCandidateWhenNothingIsPlaying() {
        val candidates = listOf(
            SessionCandidate("com.stremio.one", STATE_PAUSED),
            SessionCandidate("org.telegram.messenger", STATE_STOPPED)
        )
        assertNull(selectSessionIndex(candidates, spotify))
    }

    private companion object {
        const val STATE_STOPPED = 1
        const val STATE_PAUSED = 2
        const val STATE_PLAYING = 3
    }
}
