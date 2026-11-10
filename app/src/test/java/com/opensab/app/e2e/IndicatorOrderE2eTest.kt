package com.opensab.app.e2e

import com.opensab.domain.music.PlaybackStatus
import com.opensab.sab.protocol.projection.ActiveIndicator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The status bar, from two adapters to the bytes on the cable.
 *
 * The ordering itself is pinned at the publisher, where a write can be parked deterministically;
 * `MockGattTransport` runs its write callback under its queue lock, so parking one here would
 * stall every other write of the session. What this covers instead is the rest of the chain the unit test
 * cannot see — publisher, `PolicyScreenProjector`, `DefaultScreenProjector`, `ControlPointGateway`,
 * transport — carrying the mask faithfully, and the cluster and the app ending up agreeing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IndicatorOrderE2eTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun `music playing and a call ringing leave both bits lit on the cluster`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()

        harness.media.setStatus(PlaybackStatus.PLAYING)
        runCurrent()
        harness.callDriver.post(callerName = "Ana", callerNumber = "+34600000000")
        runCurrent()

        val expected = ActiveIndicator.maskOf(setOf(ActiveIndicator.MUSIC, ActiveIndicator.PHONE))
        assertEquals(
            "Music is MediaState == PLAYING and phone is a live call: both, at once",
            expected,
            harness.indicators.mask
        )
        assertEquals(
            "And what the cluster was last told has to be what the publisher believes it left",
            harness.indicators.mask,
            harness.btu.indicatorMasks.last()
        )
    }
}
