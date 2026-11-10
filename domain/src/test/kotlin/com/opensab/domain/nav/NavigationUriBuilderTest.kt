package com.opensab.domain.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationUriBuilderTest {

    @Test
    fun `should build direct google navigation uri with driving mode`() {
        val uri = NavigationUriBuilder.buildNavigationUri("Gasolinera Centro", NavigationApp.GOOGLE_MAPS)
        assertEquals("google.navigation:q=Gasolinera%20Centro&mode=d", uri)
    }

    @Test
    fun `should encode special characters and spaces cleanly`() {
        val uri = NavigationUriBuilder.buildNavigationUri("Calle Alcalá, 42 & Paseo", NavigationApp.GOOGLE_MAPS)
        assertEquals("google.navigation:q=Calle%20Alcal%C3%A1%2C%2042%20%26%20Paseo&mode=d", uri)
    }
}
