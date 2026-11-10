package com.opensab.app.e2e

import com.opensab.domain.audio.Cue
import com.opensab.domain.audio.GatedCuePlayer
import com.opensab.domain.audio.UnknownCueOutput
import com.opensab.sab.protocol.assignment.FunctionId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cues, over the simulated cable and through the graph Hilt builds: a handlebar press arrives
 * as a wire notification, `DefaultInputDispatcher` decodes it, `RootMenuPageProvider` and
 * `BaseStateManager` each emit what they own, and a tone lands on the player — or does not, with
 * the setting off.
 *
 * `runCurrent()` throughout, never `advanceUntilIdle()`: it does not run `backgroundScope` work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoundCuesE2eTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun `should cue the carousel and the page it opens`() = testScope.runTest {
        // Two moves of the cursor and one entry, in that order and with nothing else in between.
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        // connect() itself sounds LINK_UP; the carousel's press sequence is what this test
        // is about, so the baseline is cleared before it starts.
        harness.cuePlayer.reset()

        harness.btu.press(FunctionId.NEXT)
        runCurrent()
        harness.btu.press(FunctionId.NEXT)
        runCurrent()
        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        assertEquals(listOf(Cue.MOVE, Cue.MOVE, Cue.ENTER), harness.cuePlayer.played)
    }

    @Test
    fun `should sound nothing at all with the cues turned off`() = testScope.runTest {
        // Through the real decorator rather than through a page: the engine does not know the
        // setting exists, so this is the only place the engine's path is proved. The harness hands
        // one wrapped player, gated shut, to both the engine and the session, as production does.
        // Asserting on the *inner* recorder proves the gate cuts before the player.
        val harness = SessionHarness(
            backgroundScope,
            enginePlayer = { GatedCuePlayer(it, UnknownCueOutput) { false } }
        )
        harness.connect().getOrThrow()
        harness.handOverScreen()

        // LINK_UP tried to sound during connect() above, through the same gated player the
        // carousel below goes through: the recorder is still empty.
        assertTrue(harness.cuePlayer.played.isEmpty())

        harness.btu.press(FunctionId.NEXT)
        runCurrent()
        harness.btu.press(FunctionId.NEXT)
        runCurrent()
        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        assertTrue(harness.cuePlayer.played.isEmpty())

        // LINK_DOWN, the other half of the pair, through the same gate.
        harness.disconnect()

        assertTrue(harness.cuePlayer.played.isEmpty())
    }
}
