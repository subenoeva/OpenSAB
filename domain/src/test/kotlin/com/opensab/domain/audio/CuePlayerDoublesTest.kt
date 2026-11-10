package com.opensab.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the shared cue double from :domain's own test source set. */
class CuePlayerDoublesTest {

    @Test
    fun theMockRecordsEveryCueInOrder() {
        val player = MockCuePlayer()
        player.play(Cue.LINK_UP)
        player.play(Cue.LINK_DOWN)
        assertEquals(listOf(Cue.LINK_UP, Cue.LINK_DOWN), player.played)
    }

    @Test
    fun theProductionNoOpPlayerRecordsNothingAndDoesNotThrow() {
        NoCuePlayer.play(Cue.LINK_UP)
    }
}
