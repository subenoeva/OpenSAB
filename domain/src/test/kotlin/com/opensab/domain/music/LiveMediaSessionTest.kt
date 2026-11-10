package com.opensab.domain.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules `MediaSessionManagerSource` applies to the live sessions: which one to bind, and
 * whether binding it is a rebind. Tidal and Spotify are the apps the rider plays through.
 */
class LiveMediaSessionTest {

    private val spotify = "com.spotify.music"
    private val tidal = "com.aspiro.tidal"

    private class Token(val id: Int) {
        override fun equals(other: Any?) = other is Token && other.id == id
        override fun hashCode() = id
    }

    @Test
    fun noSessionListSelectsNothing() {
        assertNull(selectLiveSession(null, tidal))
        assertNull(selectLiveSession(emptyList(), tidal))
    }

    @Test
    fun thePreferredPackageWinsEvenWhenItIsNotTheFirstSession() {
        val sessions = listOf(
            LiveMediaSession(spotify, STATE_PAUSED, Token(1)),
            LiveMediaSession(tidal, STATE_PAUSED, Token(2))
        )

        assertEquals(1, selectLiveSession(sessions, tidal))
    }

    @Test
    fun thePreferredPackageWinsOverAnEarlierPlayingSessionWhenItPlaysToo() {
        val sessions = listOf(
            LiveMediaSession(spotify, STATE_PLAYING, Token(1)),
            LiveMediaSession(tidal, STATE_PLAYING, Token(2))
        )

        assertEquals(1, selectLiveSession(sessions, tidal))
    }

    @Test
    fun withoutPreferenceThePlayingSessionIsChosen() {
        val sessions = listOf(
            LiveMediaSession(spotify, STATE_PAUSED, Token(1)),
            LiveMediaSession(tidal, STATE_PLAYING, Token(2))
        )

        assertEquals(1, selectLiveSession(sessions, null))
    }

    @Test
    fun withoutPreferenceAndNothingPlayingTheFirstSessionIsChosen() {
        val sessions = listOf(
            LiveMediaSession(spotify, STATE_PAUSED, Token(1)),
            LiveMediaSession(tidal, STATE_PAUSED, Token(2))
        )

        assertEquals(0, selectLiveSession(sessions, null))
    }

    /** A controller with no `PlaybackState` yet is not playing: it counts as `STATE_NONE`. */
    @Test
    fun aSessionWithoutPlaybackStateIsNotPlaying() {
        val sessions = listOf(
            LiveMediaSession(spotify, null, Token(1)),
            LiveMediaSession(tidal, STATE_PLAYING, Token(2))
        )

        assertEquals(1, selectLiveSession(sessions, null))
        assertEquals(0, selectLiveSession(sessions.take(1), null))
    }

    @Test
    fun theSameTokenIsTheSameSessionAndIsNotRebound() {
        assertTrue(
            isSameSession(
                LiveMediaSession(tidal, STATE_PLAYING, Token(2)),
                LiveMediaSession(tidal, STATE_PAUSED, Token(2))
            )
        )
    }

    /** Tidal recreating its session keeps the package and changes the token: that is a rebind. */
    @Test
    fun aDifferentTokenFromTheSamePackageIsANewSession() {
        assertFalse(
            isSameSession(
                LiveMediaSession(tidal, STATE_PLAYING, Token(2)),
                LiveMediaSession(tidal, STATE_PLAYING, Token(3))
            )
        )
    }

    @Test
    fun bindingOrDroppingASessionAgainstNothingIsARebind() {
        val session = LiveMediaSession(tidal, STATE_PLAYING, Token(2))

        assertFalse(isSameSession(null, session))
        assertFalse(isSameSession(session, null))
        assertTrue(isSameSession(null, null))
    }

    private companion object {
        const val STATE_PAUSED = 2
        const val STATE_PLAYING = 3
    }
}
