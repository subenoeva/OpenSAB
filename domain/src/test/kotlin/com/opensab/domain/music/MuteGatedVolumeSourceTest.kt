package com.opensab.domain.music

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteGatedVolumeSourceTest {

    private class FakeVolumeSource : VolumeSource {
        override val level = MutableStateFlow(VolumeLevel())
        var started = false
        var stopped = false
        var raised = false
        var lowered = false
        var muteToggled = false

        override fun start() { started = true }
        override fun stop() { stopped = true }
        override fun raise() { raised = true }
        override fun lower() { lowered = true }
        override fun toggleMute() { muteToggled = true }
    }

    @Test
    fun inPauseModeToggleMuteTogglesPlaybackAndDoesNotTouchDelegate() {
        val delegate = FakeVolumeSource()
        var playbackToggled = false
        val gated = MuteGatedVolumeSource(
            delegate = delegate,
            isPauseMode = { true },
            togglePlayback = { playbackToggled = true }
        )

        gated.toggleMute()

        assertTrue(playbackToggled)
        assertFalse(delegate.muteToggled)
    }

    @Test
    fun inMuteAllModeToggleMuteDelegatesAndDoesNotTouchPlayback() {
        val delegate = FakeVolumeSource()
        var playbackToggled = false
        val gated = MuteGatedVolumeSource(
            delegate = delegate,
            isPauseMode = { false },
            togglePlayback = { playbackToggled = true }
        )

        gated.toggleMute()

        assertFalse(playbackToggled)
        assertTrue(delegate.muteToggled)
    }

    @Test
    fun transparentOperationsDelegateDirectly() {
        val delegate = FakeVolumeSource()
        val gated = MuteGatedVolumeSource(
            delegate = delegate,
            isPauseMode = { true },
            togglePlayback = {}
        )

        gated.start()
        assertTrue(delegate.started)
        gated.raise()
        assertTrue(delegate.raised)
        gated.lower()
        assertTrue(delegate.lowered)
        gated.stop()
        assertTrue(delegate.stopped)
        assertEquals(delegate.level, gated.level)
    }
}
