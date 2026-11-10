package com.opensab.domain.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundCuesSettingTest {

    @Test
    fun `should ship with the sound cues on`() {
        // On by default, because the rider who never opens Settings is the one the cues are for.
        assertTrue(AppSettings().soundCuesEnabled)
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `should keep the cues on for a settings blob written before V4`() {
        // What is on disk was written by an older build, and a key it never heard of has to come back as the default rather than as false.
        val old = """{"autoConnect":true,"autoDisconnect":true,"ctsClockSync":true}"""

        val settings = json.decodeFromString<AppSettings>(old)

        assertTrue(settings.soundCuesEnabled)
    }
}
