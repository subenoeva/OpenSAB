package com.opensab.sab.session.projection

import com.opensab.sab.protocol.projection.FrameEncoder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TestFrames stands in for `engine.LiveScreenPresets` in the projector tests. The presets live in
 * :cluster and :sab:session cannot see them, so the frames are hand-built — and this test proves
 * the bytes are the ones the preset produced.
 */
class TestFramesTest {

    @Test
    fun theHandBuiltMusicFrameEncodesToThePresetBytes() {
        assertEquals(
            MUSIC_GOLDEN_BYTES,
            FrameEncoder.encodeLiveScreen(TestFrames.music(1, "Music", "Café", "Artist", playing = true)).toHex()
        )
    }

    @Test
    fun theHandBuiltIdleFrameEncodesToThePresetBytes() {
        assertEquals(IDLE_GOLDEN_BYTES, FrameEncoder.encodeLiveScreen(TestFrames.idle(1)).toHex())
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private companion object {
        /** Captured from `LiveScreenPresets.music(1, "Music", "Café", "Artist", playing = true)`. */
        const val MUSIC_GOLDEN_BYTES =
            "01 01 0E 05 4D 75 73 69 63 00 02 0E 01 0E 04 00 0D 02 0E 05 00 0D 0F 00 05 43 61 66 " +
                "C3 A9 00 06 41 72 74 69 73 74 00"

        /** Captured the same way from `LiveScreenPresets.idle(1)`. */
        const val IDLE_GOLDEN_BYTES = "01 01 01 00 02 01 00 00 01 00 00 00"
    }
}
