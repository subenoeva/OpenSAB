package com.opensab.app.source.music

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPlaybackDebugReceiverTest {

    @Test
    fun declaresHoldMsConstantAndDefault25Seconds() {
        assertEquals("hold_ms", MediaPlaybackDebugReceiver.EXTRA_HOLD_MS)
        assertEquals("buffering_ms", MediaPlaybackDebugReceiver.EXTRA_BUFFERING_MS)
        assertEquals(25_000L, MediaPlaybackDebugReceiver.DEFAULT_HOLD_MS)
        assertEquals("com.opensab.debug.ATTACH", MediaPlaybackDebugReceiver.ACTION_ATTACH)
        assertEquals("com.opensab.debug.DETACH", MediaPlaybackDebugReceiver.ACTION_DETACH)
    }
}
