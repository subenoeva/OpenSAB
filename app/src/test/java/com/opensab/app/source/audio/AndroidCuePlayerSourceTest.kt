package com.opensab.app.source.audio

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The cue mixes on top of whatever is playing. Asking for audio focus every cue would pause or duck
 * the rider's music and reopen the race `AudioFocusLease` closes; a cue lost under music is fixed
 * with the recipe's gain, not with focus.
 */
class AndroidCuePlayerSourceTest {

    @Test
    fun theCuePlayerNeverAsksForAudioFocus() {
        val path = "src/main/java/com/opensab/app/source/audio/AndroidCuePlayer.kt"
        val text = listOf(File(path), File("app/$path")).first { it.isFile }.readText()

        assertFalse(text.contains("requestAudioFocus("))
        assertFalse(text.contains("AudioFocusRequest"))
    }
}
