package com.opensab.app.engine.indicators

import com.opensab.app.di.OrchestratorModule
import com.opensab.cluster.indicators.MusicIndicators
import com.opensab.domain.music.MockMediaControllerAdapter
import com.opensab.domain.music.TrackMetadata
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.RecordingScreenProjector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The MUSIC and MUTE bits, lit on the cluster side from the music source's state, which does not
 * own them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicIndicatorsTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val projector = RecordingScreenProjector()
    private val publisher = ActiveIndicatorsPublisher(projector)
    private val media = MockMediaControllerAdapter()
    private val music = OrchestratorModule.provideMusicManager(media)

    /** The MUSIC indicator tracks live playback: it is raised from `MediaState == PLAYING`. */
    @Test
    fun `the music bit is lit while playing`() = testScope.runTest {
        music.start(backgroundScope)
        MusicIndicators(music.currentState, publisher).start(backgroundScope)
        runCurrent()

        media.setTrack(TrackMetadata("Yesterday", "The Beatles"))
        runCurrent()
        assertEquals("A paused track must not raise the indicator", emptyList<Int>(), projector.indicatorMasks)

        media.play()
        runCurrent()
        assertEquals(listOf(ActiveIndicator.MUSIC.bit), projector.indicatorMasks)

        media.pause()
        runCurrent()
        assertEquals(listOf(ActiveIndicator.MUSIC.bit, 0x00), projector.indicatorMasks)

        music.stop()
    }

    /**
     * MUTE tracks both the mute toggle and a fader dragged to zero, in that order and from the same
     * collector as the MUSIC bit.
     */
    @Test
    fun `the mute bit is lit when muted or at zero volume`() = testScope.runTest {
        music.start(backgroundScope)
        MusicIndicators(music.currentState, publisher).start(backgroundScope)
        runCurrent()

        media.toggleMute()
        runCurrent()
        assertEquals("isMuted alone lights MUTE", listOf(ActiveIndicator.MUTE.bit), projector.indicatorMasks)

        media.toggleMute()
        runCurrent()
        assertEquals(
            "unmuting at full volume clears MUTE",
            listOf(ActiveIndicator.MUTE.bit, 0x00),
            projector.indicatorMasks
        )

        repeat(10) { media.volumeDown() }
        runCurrent()
        assertEquals(
            "zero volume lights MUTE even when not muted",
            listOf(ActiveIndicator.MUTE.bit, 0x00, ActiveIndicator.MUTE.bit),
            projector.indicatorMasks
        )

        repeat(4) { media.volumeUp() }
        runCurrent()
        assertEquals(
            "volume back up to 40 and not muted clears MUTE",
            listOf(ActiveIndicator.MUTE.bit, 0x00, ActiveIndicator.MUTE.bit, 0x00),
            projector.indicatorMasks
        )
        assertEquals(40, media.musicState.value.volumePercent)

        music.stop()
    }
}
