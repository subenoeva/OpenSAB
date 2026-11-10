package com.opensab.app.source.music

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedMediaBrowserOverrideTest {

    private val override = SimulatedMediaBrowserOverride()

    @Test
    fun simulatesTheMockPackage() {
        assertTrue(
            override.connectionFor(SimulatedMediaBrowserOverride.DEBUG_MOCK_PACKAGE) is SimulatedMediaBrowserConnection
        )
    }

    @Test
    fun leavesARealPackageAloneWhileNoBufferingIsArmed() {
        assertNull(override.connectionFor("com.spotify.music"))
    }

    @Test
    fun simulatesEveryPackageWhileBufferingIsArmed() {
        override.simulatedBufferingMs.set(5_000L)

        assertTrue(override.connectionFor("com.spotify.music") is SimulatedMediaBrowserConnection)
    }

    @Test
    fun stopsSimulatingOnceBufferingIsCleared() {
        override.simulatedBufferingMs.set(5_000L)
        override.simulatedBufferingMs.set(null)

        assertNull(override.connectionFor("com.spotify.music"))
    }
}
