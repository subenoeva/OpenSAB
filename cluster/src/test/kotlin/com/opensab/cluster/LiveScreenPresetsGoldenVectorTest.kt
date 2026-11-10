package com.opensab.cluster

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A golden wire vector whose frame comes from `LiveScreenPresets.music(...)`: an OpenSAB
 * composition rather than protocol, so it lives next to the presets and not with the codec tests.
 */
class LiveScreenPresetsGoldenVectorTest {

    @Test
    fun encodeGoldenVectorG5LiveScreenFullActions() {
        val frame = LiveScreenPresets.music(
            requestId = 0x05,
            header = "Music",
            title = "Song",
            artist = "Artist",
            playing = false
        )
        val encoded = frame.encode()

        val expected = byteArrayOf(
            0x01, 0x05, 0x0E, 0x05, 0x4D, 0x75, 0x73, 0x69, 0x63, 0x00,
            0x02, 0x0E,
            0x01, 0x01, 0x03, 0x00, 0x0D, // Left action 5-tuple
            0x02, 0x01, 0x05, 0x00, 0x0D, // Right action 5-tuple
            0x0F, 0x00,
            0x04, 0x53, 0x6F, 0x6E, 0x67, 0x00,
            0x06, 0x41, 0x72, 0x74, 0x69, 0x73, 0x74, 0x00
        )
        assertEquals(38, encoded.size)
        assertArrayEquals(expected, encoded)
    }
}
