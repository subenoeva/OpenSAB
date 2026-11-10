package com.opensab.domain.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationAppTest {

    /** N5: launching an app whose guidance OpenSAB cannot read would leave the cluster blank. */
    @Test
    fun onlyGoogleMapsCanBeLaunched() {
        assertEquals(listOf(NavigationApp.GOOGLE_MAPS), NavigationApp.entries)
    }
}
