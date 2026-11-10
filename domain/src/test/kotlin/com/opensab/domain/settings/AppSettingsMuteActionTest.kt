package com.opensab.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsMuteActionTest {
    @Test
    fun defaultMuteActionIsPauseMusic() {
        val settings = AppSettings()
        assertEquals(MuteAction.PAUSE_MUSIC, settings.muteAction)
    }

    @Test
    fun customMuteActionCanBeMuteAll() {
        val settings = AppSettings(muteAction = MuteAction.MUTE_ALL)
        assertEquals(MuteAction.MUTE_ALL, settings.muteAction)
    }
}
