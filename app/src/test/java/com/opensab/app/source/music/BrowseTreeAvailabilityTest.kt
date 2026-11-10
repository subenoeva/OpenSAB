package com.opensab.app.source.music

import com.opensab.domain.music.MediaAppInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which favourite players the settings picker may be offered for.
 *
 * The scanner reports three kinds of app under one type: those found through their
 * `MediaBrowserService`, those found only through a launcher activity in the music category, and
 * those found only by checking whether a curated package is installed. Only the first has a tree
 * to walk, and `serviceClassName` is what tells them apart.
 */
class BrowseTreeAvailabilityTest {

    @Test
    fun anAppFoundThroughItsMediaBrowserServiceCanBeBrowsed() {
        val apps = listOf(
            MediaAppInfo(
                packageName = "com.aspiro.tidal",
                appName = "TIDAL",
                serviceClassName = "com.aspiro.wamp.MediaBrowserService"
            )
        )

        assertTrue(apps.exposesBrowseTree("com.aspiro.tidal"))
    }

    @Test
    fun anAppFoundOnlyAsAnInstalledMusicPackageCannotBeBrowsed() {
        // What the curated-package and CATEGORY_APP_MUSIC passes produce: the app is a player,
        // but nothing has proven it answers a MediaBrowser connection.
        val apps = listOf(
            MediaAppInfo(packageName = "com.bandcamp.android", appName = "Bandcamp")
        )

        assertFalse(apps.exposesBrowseTree("com.bandcamp.android"))
    }

    @Test
    fun aKnownPlayerCanBeBrowsedEvenWithoutAScannedServiceClass() {
        // The browser resolves these from MediaAppScannerDefaults before asking the package
        // manager, so it will connect even when the scan did not fill the class in. The row has
        // to follow the same rule or it would hide a picker that works.
        val apps = listOf(
            MediaAppInfo(packageName = "com.spotify.music", appName = "Spotify")
        )

        assertTrue(apps.exposesBrowseTree("com.spotify.music"))
    }

    @Test
    fun noFavouritePlayerMeansNothingToBrowse() {
        val apps = listOf(
            MediaAppInfo("com.aspiro.tidal", "TIDAL", "com.aspiro.wamp.MediaBrowserService")
        )

        assertFalse(apps.exposesBrowseTree(null))
    }

    @Test
    fun aFavouriteThatIsNoLongerInstalledCannotBeBrowsed() {
        val apps = listOf(
            MediaAppInfo("com.aspiro.tidal", "TIDAL", "com.aspiro.wamp.MediaBrowserService")
        )

        assertFalse(apps.exposesBrowseTree("com.uninstalled.player"))
    }
}
