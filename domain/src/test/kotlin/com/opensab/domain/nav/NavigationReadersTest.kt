package com.opensab.domain.nav

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NavigationReadersTest {

    @Test
    fun mapsIsReadByTheGoogleMapsReader() {
        assertSame(GoogleMapsReader, NavigationReaders.readerFor("com.google.android.apps.maps"))
    }

    /** No dump of their notification yet: an app without a reader is not guessed. */
    @Test
    fun wazeAndCalimotoHaveNoReader() {
        assertNull(NavigationReaders.readerFor("com.waze"))
        assertNull(NavigationReaders.readerFor("com.calimoto.calimoto"))
    }
}
