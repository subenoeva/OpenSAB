package com.opensab.app.source.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaEngineSeamsContractTest {

    @Test
    fun resolvesKnownPlayerServiceComponentsDirectly() {
        val spotify = MediaAppScannerDefaults.resolveServiceComponent("com.spotify.music")
        assertNotNull(spotify)
        assertEquals("com.spotify.music.service.SpotifyMediaBrowserServiceCompat", spotify?.className)

        val ytMusic = MediaAppScannerDefaults.resolveServiceComponent("com.google.android.apps.youtube.music")
        assertNotNull(ytMusic)
        assertEquals("com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService", ytMusic?.className)
    }

    @Test
    fun fakeWakeLockTracksAcquisitionAndRelease() {
        val wakeLock = FakeWakeLockSeam()
        assertFalse(wakeLock.isHeld)
        wakeLock.acquire(10_000L)
        assertTrue(wakeLock.isHeld)
        assertEquals(10_000L, wakeLock.lastTimeoutMs)
        wakeLock.release()
        assertFalse(wakeLock.isHeld)
    }

    @Test
    fun fakeAudioKeyDispatcherTracksPlayDispatch() {
        val dispatcher = FakeAudioKeyDispatcher()
        assertFalse(dispatcher.dispatchedPlayKey)
        dispatcher.dispatchPlayKey()
        assertTrue(dispatcher.dispatchedPlayKey)
    }

    @Test
    fun androidAppOpenerSeamReturnsRejectedWhenKeyguardLockedWithoutOverlayPermission() {
        var started = false
        val dummyIntent = Any()
        val seam = AndroidAppOpenerSeam(
            context = null,
            launchIntentProvider = { dummyIntent },
            activityStarter = { started = true },
            isKeyguardLockedProvider = { true },
            canDrawOverlaysProvider = { false }
        )

        val result = seam.openApp("com.spotify.music")
        assertEquals(OpenRequestResult.REJECTED, result)
        assertFalse("Activity must not be started when BAL would block it", started)
    }

    @Test
    fun androidAppOpenerSeamReturnsRequestedWhenOverlayGrantedEvenIfKeyguardLocked() {
        var started = false
        val dummyIntent = Any()
        val seam = AndroidAppOpenerSeam(
            context = null,
            launchIntentProvider = { dummyIntent },
            activityStarter = { started = true },
            isKeyguardLockedProvider = { true },
            canDrawOverlaysProvider = { true }
        )

        val result = seam.openApp("com.spotify.music")
        assertEquals(OpenRequestResult.REQUESTED, result)
        assertTrue("Activity should be started when overlay permission is granted", started)
    }
}
