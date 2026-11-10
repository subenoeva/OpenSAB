package com.opensab.domain.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPackageFilterTest {

    @Test
    fun blacklistsVideoStreamingApplications() {
        val videoApps = listOf(
            "com.stremio.one",
            "com.google.android.youtube",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "com.plexapp.android",
            "com.crunchyroll.crunchyroid",
            "com.disney.disneyplus",
            "com.hbo.hbonow",
            "com.wbd.stream",
            "tv.twitch.android.app",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "is.xyz.mpv"
        )
        for (pkg in videoApps) {
            assertTrue("Expected $pkg to be blacklisted", MediaPackageFilter.isBlacklisted(pkg))
            assertFalse("Expected $pkg not to be allowed", MediaPackageFilter.isAllowed(pkg))
        }
    }

    @Test
    fun blacklistsMessengerAndVoipApplications() {
        val messengerApps = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.discord",
            "com.facebook.orca",
            "org.thoughtcrime.securesms"
        )
        for (pkg in messengerApps) {
            assertTrue("Expected $pkg to be blacklisted", MediaPackageFilter.isBlacklisted(pkg))
            assertFalse("Expected $pkg not to be allowed", MediaPackageFilter.isAllowed(pkg))
        }
    }

    @Test
    fun blacklistsWebBrowsers() {
        val browsers = listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "org.mozilla.fenix",
            "com.opera.browser",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser"
        )
        for (pkg in browsers) {
            assertTrue("Expected $pkg to be blacklisted", MediaPackageFilter.isBlacklisted(pkg))
        }
    }

    @Test
    fun allowsLegitimateMusicPlayers() {
        val musicApps = listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.amazon.mp3",
            "com.apple.android.music",
            "com.aspiro.tidal",
            "deezer.android.app",
            "com.soundcloud.android",
            "de.danoeh.antennapod",
            "au.com.shiftyjelly.pocketcasts",
            "in.krosbits.musicolet",
            "com.maxmpz.audioplayer",
            "org.videolan.vlc",
            "app.revanced.android.apps.youtube.music",
            "app.morphe.android.apps.youtube.music",
            "com.vimusic.app",
            "com.innertune.app",
            "org.schabi.newpipe"
        )
        for (pkg in musicApps) {
            assertFalse("Expected $pkg NOT to be blacklisted", MediaPackageFilter.isBlacklisted(pkg))
            assertTrue("Expected $pkg to be allowed", MediaPackageFilter.isAllowed(pkg))
        }
    }

    @Test
    fun noCuratedMusicPackagesAreBlacklisted() {
        for (pkg in MediaPackageFilter.CURATED_MUSIC_PACKAGES) {
            assertFalse(
                "Curated music package $pkg must not be blacklisted",
                MediaPackageFilter.isBlacklisted(pkg)
            )
        }
    }
}
