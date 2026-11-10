package com.opensab.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColdStartPinTest {

    @Test
    fun `should answer the pin for the app that issued it`() {
        val settings = AppSettings(
            coldStartMediaId = "MY_COLLECTION_TRACKS",
            coldStartMediaPackage = "com.aspiro.tidal"
        )

        assertEquals("MY_COLLECTION_TRACKS", settings.coldStartMediaIdFor("com.aspiro.tidal"))
    }

    @Test
    fun `should never hand a pin to another app`() {
        // A media ID means nothing outside the app that issued it.
        val settings = AppSettings(
            coldStartMediaId = "MY_COLLECTION_TRACKS",
            coldStartMediaPackage = "com.aspiro.tidal"
        )

        assertNull(settings.coldStartMediaIdFor("com.spotify.music"))
    }

    @Test
    fun `should answer nothing when no pin was chosen`() {
        assertNull(AppSettings(coldStartMediaPackage = "com.aspiro.tidal").coldStartMediaIdFor("com.aspiro.tidal"))
    }
}
