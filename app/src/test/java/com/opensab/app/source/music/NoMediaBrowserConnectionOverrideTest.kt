package com.opensab.app.source.music

import org.junit.Assert.assertNull
import org.junit.Test

class NoMediaBrowserConnectionOverrideTest {

    @Test
    fun neverReplacesARealConnection() {
        assertNull(NoMediaBrowserConnectionOverride.connectionFor("com.opensab.debug.mock"))
        assertNull(NoMediaBrowserConnectionOverride.connectionFor("com.spotify.music"))
    }
}
