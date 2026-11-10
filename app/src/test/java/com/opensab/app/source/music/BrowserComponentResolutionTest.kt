package com.opensab.app.source.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which `MediaBrowserService` component OpenSAB binds to.
 *
 * Two sources can name it: the package manager, which reports what is installed right now, and
 * [MediaAppScannerDefaults.KNOWN_PLAYERS], which is a table compiled into the app. They disagree
 * whenever a player moves its service between releases, and then the order decides whether the
 * cold start works or falls through to opening the app on screen.
 */
class BrowserComponentResolutionTest {

    @Test
    fun theLivePackageManagerWinsOverTheCompiledTable() {
        // Spotify moved its service out of com.spotify.music.service; the table still names the
        // old class, and binding to it fails with "not found". Whatever is installed wins.
        val installed = MediaServiceComponent(
            packageName = "com.spotify.music",
            className = "com.spotify.mediabrowserservice.mediabrowserservice.SpotifyMediaBrowserService"
        )

        val resolved = MediaAppScannerDefaults.resolveBrowserComponent(
            packageName = "com.spotify.music",
            fromPackageManager = installed
        )

        assertEquals(installed, resolved)
    }

    @Test
    fun theTableIsUsedWhenThePackageManagerReportsNothing() {
        // Package visibility can hide a service the app is nonetheless allowed to bind to, so the
        // table stays as a fallback rather than being deleted.
        val resolved = MediaAppScannerDefaults.resolveBrowserComponent(
            packageName = "com.spotify.music",
            fromPackageManager = null
        )

        assertEquals(
            MediaServiceComponent(
                packageName = "com.spotify.music",
                className = "com.spotify.music.service.SpotifyMediaBrowserServiceCompat"
            ),
            resolved
        )
    }

    @Test
    fun anUnknownPlayerThePackageManagerCannotSeeResolvesToNothing() {
        val resolved = MediaAppScannerDefaults.resolveBrowserComponent(
            packageName = "com.example.unknown",
            fromPackageManager = null
        )

        assertNull(resolved)
    }

    @Test
    fun anUnknownPlayerIsTakenFromThePackageManagerAlone() {
        // Tidal is not in the table: it only ever works through the package manager.
        val installed = MediaServiceComponent(
            packageName = "com.aspiro.tidal",
            className = "com.aspiro.wamp.MediaBrowserService"
        )

        val resolved = MediaAppScannerDefaults.resolveBrowserComponent(
            packageName = "com.aspiro.tidal",
            fromPackageManager = installed
        )

        assertEquals(installed, resolved)
    }
}
