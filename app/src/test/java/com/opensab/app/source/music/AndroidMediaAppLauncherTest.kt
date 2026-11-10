package com.opensab.app.source.music

import com.opensab.domain.music.MediaSessionSnapshot
import com.opensab.domain.music.MediaSessionSource
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.TrackMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidMediaAppLauncherTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeWakeLock: FakeWakeLockSeam
    private lateinit var fakeBrowserFactory: FakeMediaBrowserClientFactory
    private lateinit var fakeAudioKeyDispatcher: FakeAudioKeyDispatcher
    private lateinit var fakeAppOpener: FakeAppOpenerSeam
    private lateinit var fakeSessionSource: FakeMediaSessionSource
    private lateinit var launcher: AndroidMediaAppLauncher

    @Before
    fun setUp() {
        fakeWakeLock = FakeWakeLockSeam(clock = { testDispatcher.scheduler.currentTime })
        fakeBrowserFactory = FakeMediaBrowserClientFactory()
        fakeAudioKeyDispatcher = FakeAudioKeyDispatcher()
        fakeAppOpener = FakeAppOpenerSeam()
        fakeSessionSource = FakeMediaSessionSource()
        launcher = AndroidMediaAppLauncher(
            wakeLockSeam = fakeWakeLock,
            browserFactory = fakeBrowserFactory,
            audioKeyDispatcher = fakeAudioKeyDispatcher,
            coroutineScope = testScope,
            dispatcher = testDispatcher
        )
    }

    private fun createLauncherWithAwait(
        appOpener: AppOpenerSeam = fakeAppOpener,
        sessionSource: MediaSessionSource = fakeSessionSource
    ): AndroidMediaAppLauncher = AndroidMediaAppLauncher(
        wakeLockSeam = fakeWakeLock,
        browserFactory = fakeBrowserFactory,
        audioKeyDispatcher = fakeAudioKeyDispatcher,
        coroutineScope = testScope,
        dispatcher = testDispatcher,
        appOpener = appOpener,
        sessionSource = sessionSource
    )

    @Test
    fun acquiresWakeLockAndTransitionsToConnectingOnLaunch() = testScope.runTest {
        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        assertTrue("WakeLock must be acquired on launch", fakeWakeLock.isHeld)
        assertEquals(10_000L, fakeWakeLock.lastTimeoutMs)
        assertEquals(LauncherState.CONNECTING, launcher.launcherState.value)
    }

    @Test
    fun playsItemDirectlyWhenRootItemIsPlayable() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        conn.rootItems = listOf(MediaBrowserItem("track-1", isPlayable = true, isBrowsable = false))

        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        conn.simulateConnected()
        testScheduler.runCurrent()

        assertEquals("track-1", conn.lastPlayedMediaId)
        assertTrue(
            "Must unsubscribe from root immediately after reading",
            conn.unsubscribedCategories.contains("root")
        )
    }

    @Test
    fun drillsDownOneLevelWhenRootItemIsBrowsable() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        conn.rootItems =
            listOf(MediaBrowserItem("recent-category", isPlayable = false, isBrowsable = true))
        conn.childItems["recent-category"] =
            listOf(MediaBrowserItem("song-42", isPlayable = true, isBrowsable = false))

        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        conn.simulateConnected()
        testScheduler.runCurrent()

        assertEquals("song-42", conn.lastPlayedMediaId)
        assertTrue(
            "Must unsubscribe from browsable category after drill-down",
            conn.unsubscribedCategories.contains("recent-category")
        )
        assertTrue(
            "Must unsubscribe from root after drill-down",
            conn.unsubscribedCategories.contains("root")
        )
    }

    @Test
    fun prefersRecentContainerOverFirstBrowsableChild() = testScope.runTest {
        // Real Tidal ROOT_AUTO layout: the home page sits at index 0, but the recently
        // played container is what the rider expects to hear on a cold start.
        val conn = fakeBrowserFactory.getOrCreate("com.aspiro.tidal")
        conn.rootId = "ROOT_AUTO"
        conn.rootItems = listOf(
            MediaBrowserItem("HOME_V2::home_page_v2_id", isPlayable = false, isBrowsable = true),
            MediaBrowserItem(
                "RECENTLY_PLAYED::home/pages/CONTINUE_LISTEN_TO/view-all",
                isPlayable = false,
                isBrowsable = true
            ),
            MediaBrowserItem("DYNAMIC::pages/car_explore", isPlayable = false, isBrowsable = true),
            MediaBrowserItem(
                "MY_COLLECTION::android_auto_content_id",
                isPlayable = false,
                isBrowsable = true
            )
        )
        conn.childItems["HOME_V2::home_page_v2_id"] =
            listOf(MediaBrowserItem("TRACK::545231661", isPlayable = true, isBrowsable = false))
        conn.childItems["RECENTLY_PLAYED::home/pages/CONTINUE_LISTEN_TO/view-all"] =
            listOf(MediaBrowserItem("TRACK::777", isPlayable = true, isBrowsable = false))

        launcher.launchAndPlay("com.aspiro.tidal")
        testScheduler.runCurrent()

        conn.simulateConnected(BrowserReady(rootId = "ROOT_AUTO"))
        testScheduler.runCurrent()

        assertEquals("TRACK::777", conn.lastPlayedMediaId)
    }

    @Test
    fun fallsBackToNextContainerWhenRecentContainerHasNoPlayableTrack() = testScope.runTest {
        // A fresh account has an empty history. Falling through to play() would stall the
        // cold start, because a cold session has no queue to resume.
        val conn = fakeBrowserFactory.getOrCreate("com.aspiro.tidal")
        conn.rootId = "ROOT_AUTO"
        conn.rootItems = listOf(
            MediaBrowserItem("HOME_V2::home_page_v2_id", isPlayable = false, isBrowsable = true),
            MediaBrowserItem(
                "RECENTLY_PLAYED::home/pages/CONTINUE_LISTEN_TO/view-all",
                isPlayable = false,
                isBrowsable = true
            )
        )
        conn.childItems["RECENTLY_PLAYED::home/pages/CONTINUE_LISTEN_TO/view-all"] = emptyList()
        conn.childItems["HOME_V2::home_page_v2_id"] =
            listOf(MediaBrowserItem("TRACK::545231661", isPlayable = true, isBrowsable = false))

        launcher.launchAndPlay("com.aspiro.tidal")
        testScheduler.runCurrent()

        conn.simulateConnected(BrowserReady(rootId = "ROOT_AUTO"))
        testScheduler.runCurrent()

        assertEquals("TRACK::545231661", conn.lastPlayedMediaId)
        assertFalse(
            "Must not fall through to a queueless play() while another container is untried",
            conn.genericPlayCalled
        )
    }

    @Test
    fun fallsBackToFirstBrowsableChildWhenNoRecentContainerExists() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        conn.rootItems = listOf(
            MediaBrowserItem("browse-1", isPlayable = false, isBrowsable = true),
            MediaBrowserItem("browse-2", isPlayable = false, isBrowsable = true)
        )
        conn.childItems["browse-1"] =
            listOf(MediaBrowserItem("song-1", isPlayable = true, isBrowsable = false))
        conn.childItems["browse-2"] =
            listOf(MediaBrowserItem("song-2", isPlayable = true, isBrowsable = false))

        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        conn.simulateConnected()
        testScheduler.runCurrent()

        assertEquals("song-1", conn.lastPlayedMediaId)
    }

    @Test
    fun dispatchesPinnedMediaIdWithoutBrowsingTheTree() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.aspiro.tidal")
        conn.rootId = "ROOT_AUTO"
        conn.rootItems = listOf(
            MediaBrowserItem("HOME_V2::home_page_v2_id", isPlayable = false, isBrowsable = true)
        )
        conn.childItems["HOME_V2::home_page_v2_id"] =
            listOf(MediaBrowserItem("TRACK::545231661", isPlayable = true, isBrowsable = false))
        val launcher = AndroidMediaAppLauncher(
            wakeLockSeam = fakeWakeLock,
            browserFactory = fakeBrowserFactory,
            audioKeyDispatcher = fakeAudioKeyDispatcher,
            coroutineScope = testScope,
            dispatcher = testDispatcher,
            coldStartMediaIdProvider = { "MY_COLLECTION_TRACKS" }
        )

        launcher.launchAndPlay("com.aspiro.tidal")
        testScheduler.runCurrent()

        conn.simulateConnected(BrowserReady(rootId = "ROOT_AUTO"))
        testScheduler.runCurrent()

        assertEquals("MY_COLLECTION_TRACKS", conn.lastPlayedMediaId)
        assertTrue(
            "A pinned ID must not walk the tree",
            conn.unsubscribedCategories.isEmpty()
        )
    }

    @Test
    fun fallsBackToTheCascadeWhenThePinnedMediaIdNeverPlays() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.aspiro.tidal")
        conn.rootId = "ROOT_AUTO"
        conn.rootItems = listOf(
            MediaBrowserItem("HOME_V2::home_page_v2_id", isPlayable = false, isBrowsable = true)
        )
        conn.childItems["HOME_V2::home_page_v2_id"] =
            listOf(MediaBrowserItem("TRACK::545231661", isPlayable = true, isBrowsable = false))
        val launcher = AndroidMediaAppLauncher(
            wakeLockSeam = fakeWakeLock,
            browserFactory = fakeBrowserFactory,
            audioKeyDispatcher = fakeAudioKeyDispatcher,
            coroutineScope = testScope,
            dispatcher = testDispatcher,
            coldStartMediaIdProvider = { "PLAYLIST::deleted" }
        )

        launcher.launchAndPlay("com.aspiro.tidal")
        testScheduler.runCurrent()

        conn.simulateConnected(BrowserReady(rootId = "ROOT_AUTO"))
        testScheduler.runCurrent()
        assertEquals("PLAYLIST::deleted", conn.lastPlayedMediaId)

        // No STATE_PLAYING arrives; the browse watchdog fires.
        testScheduler.advanceTimeBy(10_001.milliseconds)
        testScheduler.runCurrent()

        assertEquals("TRACK::545231661", conn.lastPlayedMediaId)
    }

    @Test
    fun waitsForASuspendedPinReadBeforeDispatching() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.aspiro.tidal")
        val pin = CompletableDeferred<String?>()
        val launcher = AndroidMediaAppLauncher(
            wakeLockSeam = fakeWakeLock,
            browserFactory = fakeBrowserFactory,
            audioKeyDispatcher = fakeAudioKeyDispatcher,
            coroutineScope = testScope,
            dispatcher = testDispatcher,
            coldStartMediaIdProvider = { pin.await() }
        )

        launcher.launchAndPlay("com.aspiro.tidal")
        testScheduler.runCurrent()
        conn.simulateConnected(BrowserReady(rootId = "ROOT_AUTO"))
        testScheduler.runCurrent()

        assertNull("Nothing is dispatched while the settings are still being read", conn.lastPlayedMediaId)
        assertTrue("The tree is not walked while the settings are still being read", conn.unsubscribedCategories.isEmpty())

        pin.complete("MY_COLLECTION_TRACKS")
        testScheduler.runCurrent()

        assertEquals("MY_COLLECTION_TRACKS", conn.lastPlayedMediaId)
    }

    @Test
    fun dropsAPinThatArrivesAfterTheAttemptWasReplaced() = testScope.runTest {
        val tidal = fakeBrowserFactory.getOrCreate("com.aspiro.tidal")
        fakeBrowserFactory.getOrCreate("com.spotify.music")
        val pin = CompletableDeferred<String?>()
        val launcher = AndroidMediaAppLauncher(
            wakeLockSeam = fakeWakeLock,
            browserFactory = fakeBrowserFactory,
            audioKeyDispatcher = fakeAudioKeyDispatcher,
            coroutineScope = testScope,
            dispatcher = testDispatcher,
            coldStartMediaIdProvider = { pkg -> if (pkg == "com.aspiro.tidal") pin.await() else null }
        )

        launcher.launchAndPlay("com.aspiro.tidal")
        testScheduler.runCurrent()
        tidal.simulateConnected(BrowserReady(rootId = "ROOT_AUTO"))
        testScheduler.runCurrent()

        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()
        pin.complete("MY_COLLECTION_TRACKS")
        testScheduler.runCurrent()

        assertNull("A pin read for a dead attempt must never be dispatched", tidal.lastPlayedMediaId)
    }

    @Test
    fun ignoresAPinnedMediaIdBelongingToAnotherApp() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        conn.rootItems = listOf(MediaBrowserItem("browse-1", isPlayable = false, isBrowsable = true))
        conn.childItems["browse-1"] =
            listOf(MediaBrowserItem("song-1", isPlayable = true, isBrowsable = false))
        // The provider resolves the package match and returns null for a foreign pin.
        val launcher = AndroidMediaAppLauncher(
            wakeLockSeam = fakeWakeLock,
            browserFactory = fakeBrowserFactory,
            audioKeyDispatcher = fakeAudioKeyDispatcher,
            coroutineScope = testScope,
            dispatcher = testDispatcher,
            coldStartMediaIdProvider = { pkg -> "MY_COLLECTION_TRACKS".takeIf { pkg == "com.aspiro.tidal" } }
        )

        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        conn.simulateConnected()
        testScheduler.runCurrent()

        assertEquals("song-1", conn.lastPlayedMediaId)
    }

    @Test
    fun releasesWakeLockAndDisconnectsWhenStatePlayingReached() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        var completeSuccess: Boolean? = null

        launcher.launchAndPlay("com.spotify.music") { success -> completeSuccess = success }
        testScheduler.runCurrent()

        conn.simulateConnected()
        testScheduler.runCurrent()

        conn.simulatePlaybackState(3) // STATE_PLAYING
        testScheduler.advanceTimeBy(1_500.milliseconds)
        testScheduler.runCurrent()

        assertFalse("WakeLock must be released upon playing", fakeWakeLock.isHeld)
        assertTrue("Browser must be disconnected", conn.isDisconnected)
        assertEquals(LauncherState.ACTIVE, launcher.launcherState.value)
        assertEquals(true, completeSuccess)
    }

    @Test
    fun escalatesToLevel3WhenConnectionWatchdogFiresAfter5000ms() = testScope.runTest {
        fakeAudioKeyDispatcher.lastActivePackage = "com.spotify.music"

        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(5001.milliseconds)
        testScheduler.runCurrent()

        assertTrue(
            "Must fall back to Level 3 key dispatch after watchdog timeout",
            fakeAudioKeyDispatcher.dispatchedPlayKey
        )
        assertFalse("WakeLock must be released after watchdog and fallback", fakeWakeLock.isHeld)
    }

    @Test
    fun abortsLevel3WhenTargetPackageDoesNotMatchAudioKeyIsolation() = testScope.runTest {
        fakeAudioKeyDispatcher.lastActivePackage = "com.whatsapp"

        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        conn.simulateConnectionFailed()
        testScheduler.runCurrent()

        assertFalse(
            "Level 3 must abort when last active package does not match favorite",
            fakeAudioKeyDispatcher.dispatchedPlayKey
        )
        assertEquals(LauncherState.IDLE, launcher.launcherState.value)
        assertFalse(fakeWakeLock.isHeld)
    }

    /** The kernel lock must outlive every live attempt, up to the 30 s deadline that ends it. */
    @Test
    fun theWakeLockIsHeldForAsLongAsTheAttemptIsAlive() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()
        conn.simulateConnected()
        testScheduler.runCurrent()

        // A player that connects and then says nothing at all.
        repeat(62) { step ->
            val alive = launcher.launcherState.value == LauncherState.CONNECTING ||
                launcher.launcherState.value == LauncherState.BUFFERING
            if (alive) {
                assertTrue(
                    "attempt alive at ${step * 500} ms in ${launcher.launcherState.value} without a WakeLock",
                    fakeWakeLock.isHeld
                )
            }
            testScheduler.advanceTimeBy(500.milliseconds)
            testScheduler.runCurrent()
        }
    }

    @Test
    fun theWakeLockOutlivesALateBufferThatEscalatesToTheSessionWait() = testScope.runTest {
        val escalating = createLauncherWithAwait()
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        escalating.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()
        conn.simulateConnected()
        testScheduler.runCurrent()

        repeat(62) { step ->
            if (step == 8) conn.simulatePlaybackState(6) // STATE_BUFFERING at 4 s, never plays
            testScheduler.runCurrent()
            val alive = escalating.launcherState.value == LauncherState.CONNECTING ||
                escalating.launcherState.value == LauncherState.BUFFERING
            if (alive) {
                assertTrue(
                    "attempt alive at ${step * 500} ms in ${escalating.launcherState.value} without a WakeLock",
                    fakeWakeLock.isHeld
                )
            }
            testScheduler.advanceTimeBy(500.milliseconds)
            testScheduler.runCurrent()
        }
    }

    @Test
    fun bufferingCancelsTheBrowseWatchdogSoTheAdaptiveGraceIsHonoured() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        conn.simulateConnected()
        testScheduler.runCurrent()

        // Buffering takes 6s
        testScheduler.advanceTimeBy(6.seconds)
        testScheduler.runCurrent()
        conn.simulatePlaybackState(6) // STATE_BUFFERING
        testScheduler.runCurrent()

        assertEquals(LauncherState.BUFFERING, launcher.launcherState.value)
        assertEquals(15_000L, fakeWakeLock.lastTimeoutMs)

        // Adaptive grace extends deadline past browse watchdog
        testScheduler.advanceTimeBy(4_100.milliseconds)
        testScheduler.runCurrent()
        assertFalse(
            "The browse watchdog must not cut a buffering session short",
            conn.isDisconnected
        )
    }

    @Test
    fun adaptiveBufferingWatchdogStillFiresAfter15000ms() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        conn.simulateConnected()
        testScheduler.runCurrent()

        conn.simulatePlaybackState(6) // STATE_BUFFERING
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(14.seconds)
        testScheduler.runCurrent()
        assertFalse("Still buffering inside the 15s grace", conn.isDisconnected)

        testScheduler.advanceTimeBy(1_100.milliseconds)
        testScheduler.runCurrent()
        assertTrue("After 15s the adaptive watchdog must disconnect", conn.isDisconnected)
    }

    @Test
    fun abortsImmediatelyWhenRemoteEmitsStateError() = testScope.runTest {
        fakeAudioKeyDispatcher.lastActivePackage = "com.spotify.music"
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")

        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        conn.simulateConnected()
        testScheduler.runCurrent()

        conn.simulatePlaybackState(7) // STATE_ERROR
        testScheduler.runCurrent()

        assertTrue(conn.isDisconnected)
        assertTrue(fakeAudioKeyDispatcher.dispatchedPlayKey)
    }

    @Test
    fun completesImmediatelyWithSuccessWhenInitialStateIsAlreadyPlaying() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        var completed: Boolean? = null

        launcher.launchAndPlay("com.spotify.music") { completed = it }
        testScheduler.runCurrent()

        // Remote player connects already in STATE_PLAYING (3)
        conn.simulateConnected(BrowserReady(rootId = "root", initialState = 3))
        testScheduler.runCurrent()

        assertEquals(true, completed)
        assertEquals(LauncherState.ACTIVE, launcher.launcherState.value)
        assertFalse(fakeWakeLock.isHeld)
        assertTrue(conn.isDisconnected)
        assertEquals(0, conn.unsubscribedCategories.size)
    }

    @Test
    fun resetsCleanlyAndReleasesHeldWakeLock() = testScope.runTest {
        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()
        assertTrue(fakeWakeLock.isHeld)

        launcher.reset()
        testScheduler.runCurrent()

        assertFalse(fakeWakeLock.isHeld)
        assertEquals(LauncherState.IDLE, launcher.launcherState.value)
    }

    @Test
    fun appOpenerAwaitsSessionAndResumesToPlayingSuccessfully() = testScope.runTest {
        val launcherWithAwait = createLauncherWithAwait()
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        var completed: Boolean? = null

        launcherWithAwait.launchAndPlay("com.spotify.music") { completed = it }
        testScheduler.runCurrent()

        // Browser fails -> escalates to AppOpener -> enters awaitSession
        conn.simulateConnectionFailed()
        testScheduler.runCurrent()

        assertEquals("com.spotify.music", fakeAppOpener.lastOpenedPackage)
        assertEquals("com.spotify.music", fakeSessionSource.preferredPackage)
        assertEquals(1, fakeSessionSource.startCount)
        assertTrue("WakeLock must be held while awaiting session", fakeWakeLock.isHeld)

        // At 800ms, Spotify MediaSession appears in PAUSED
        testScheduler.advanceTimeBy(800.milliseconds)
        fakeSessionSource.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata("Song", "Artist"),
                status = PlaybackStatus.PAUSED
            )
        )
        testScheduler.runCurrent()

        // Must dispatch play() to sessionSource
        assertEquals(1, fakeSessionSource.playCount)
        assertFalse("Must NOT dispatch global play key", fakeAudioKeyDispatcher.dispatchedPlayKey)
        assertNull("Attempt should not be finished yet until PLAYING", completed)

        // Spotify transitions to PLAYING
        fakeSessionSource.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata("Song", "Artist"),
                status = PlaybackStatus.PLAYING
            )
        )
        testScheduler.advanceTimeBy(1_500.milliseconds)
        testScheduler.runCurrent()

        assertEquals(true, completed)
        assertEquals(LauncherState.ACTIVE, launcherWithAwait.launcherState.value)
        assertFalse("WakeLock must be released", fakeWakeLock.isHeld)
        assertEquals(1, fakeSessionSource.stopCount)
    }

    @Test
    fun appOpenerTimesOutAfter7000msWhenNoSessionAppears() = testScope.runTest {
        val launcherWithAwait = createLauncherWithAwait()
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        var completed: Boolean? = null

        launcherWithAwait.launchAndPlay("com.spotify.music") { completed = it }
        testScheduler.runCurrent()

        conn.simulateConnectionFailed()
        testScheduler.runCurrent()

        assertTrue(fakeWakeLock.isHeld)
        assertEquals(1, fakeSessionSource.startCount)

        // Advance 7000ms: watchdog fires
        testScheduler.advanceTimeBy(7001.milliseconds)
        testScheduler.runCurrent()

        assertEquals(false, completed)
        assertEquals(LauncherState.IDLE, launcherWithAwait.launcherState.value)
        assertFalse("WakeLock must be released after timeout", fakeWakeLock.isHeld)
        assertEquals(1, fakeSessionSource.stopCount)
    }

    @Test
    fun appOpenerAwaitsSessionIgnoresForeignAppSessions() = testScope.runTest {
        val launcherWithAwait = createLauncherWithAwait()
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        var completed: Boolean? = null

        launcherWithAwait.launchAndPlay("com.spotify.music") { completed = it }
        testScheduler.runCurrent()

        conn.simulateConnectionFailed()
        testScheduler.runCurrent()

        // Foreign app (com.whatsapp) publishes a session
        fakeSessionSource.emit(
            MediaSessionSnapshot(
                packageName = "com.whatsapp",
                metadata = TrackMetadata("Voice Note", "Contact"),
                status = PlaybackStatus.PLAYING
            )
        )
        testScheduler.runCurrent()

        // Foreign app must be ignored
        assertNull(completed)
        assertEquals(0, fakeSessionSource.playCount)
        assertTrue(fakeWakeLock.isHeld)

        // Watchdog expires without favorite app session
        testScheduler.advanceTimeBy(7001.milliseconds)
        testScheduler.runCurrent()

        assertEquals(false, completed)
        assertEquals(LauncherState.IDLE, launcherWithAwait.launcherState.value)
        assertFalse(fakeWakeLock.isHeld)
    }

    @Test
    fun appOpenerIgnoresStaleSessionSnapshotFromBeforeOpen() = testScope.runTest {
        val staleSnapshot = MediaSessionSnapshot(
            packageName = "com.spotify.music",
            metadata = TrackMetadata("Old Song", "Artist"),
            status = PlaybackStatus.PAUSED
        )
        fakeSessionSource.emit(staleSnapshot)

        val launcherWithAwait = createLauncherWithAwait()
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        var completed: Boolean? = null

        launcherWithAwait.launchAndPlay("com.spotify.music") { completed = it }
        testScheduler.runCurrent()

        conn.simulateConnectionFailed()
        testScheduler.runCurrent()

        // Stale snapshot present in StateFlow before openApp must be ignored
        assertEquals("Must not dispatch play to stale snapshot", 0, fakeSessionSource.playCount)
        assertNull(completed)

        // Now Spotify starts up fresh and emits an updated snapshot
        val freshSnapshot = MediaSessionSnapshot(
            packageName = "com.spotify.music",
            metadata = TrackMetadata("New Song", "Artist"),
            status = PlaybackStatus.PAUSED
        )
        fakeSessionSource.emit(freshSnapshot)
        testScheduler.runCurrent()

        assertEquals("Must dispatch play to fresh snapshot", 1, fakeSessionSource.playCount)

        // Spotify starts playing
        fakeSessionSource.emit(freshSnapshot.copy(status = PlaybackStatus.PLAYING))
        testScheduler.advanceTimeBy(1_500.milliseconds)
        testScheduler.runCurrent()

        assertEquals(true, completed)
        assertEquals(LauncherState.ACTIVE, launcherWithAwait.launcherState.value)
        assertFalse(fakeWakeLock.isHeld)
    }

    @Test
    fun appOpenerDispatchesPlayWhenThePlayerReturnsWithTheVerySameTrack() = testScope.runTest {
        val stale = MediaSessionSnapshot(
            packageName = "com.spotify.music",
            metadata = TrackMetadata("Song", "Artist"),
            status = PlaybackStatus.PAUSED
        )
        fakeSessionSource.emit(stale)

        val launcherWithAwait = createLauncherWithAwait()
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")

        launcherWithAwait.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        conn.simulateConnectionFailed()
        testScheduler.runCurrent()

        // Initial StateFlow replay ignored
        assertEquals("Must not dispatch play to the replayed snapshot", 0, fakeSessionSource.playCount)

        // New emission with same track in PAUSED must trigger play()
        fakeSessionSource.emit(null)
        testScheduler.runCurrent()
        fakeSessionSource.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata("Song", "Artist"),
                status = PlaybackStatus.PAUSED
            )
        )
        testScheduler.runCurrent()

        assertEquals("A fresh emission with identical content must be honoured", 1, fakeSessionSource.playCount)
    }

    @Test
    fun escalatesOnlyOnceEvenIfSeveralTriggersFireForTheSameAttempt() = testScope.runTest {
        val launcherWithAwait = createLauncherWithAwait()
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")

        launcherWithAwait.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        // First trigger: connection fails.
        conn.simulateConnectionFailed()
        testScheduler.runCurrent()
        assertEquals(1, fakeAppOpener.openCount)

        // Second trigger: remote service responds late and then reports STATE_ERROR.
        conn.simulateConnected()
        testScheduler.runCurrent()
        conn.simulatePlaybackState(7) // STATE_ERROR
        testScheduler.runCurrent()

        assertEquals("openApp must not run twice for one attempt", 1, fakeAppOpener.openCount)
        assertEquals("sessionSource must not be started twice", 1, fakeSessionSource.startCount)
    }

    @Test
    fun sessionSourceStartAndStopStayBalancedAcrossTheWholeAttempt() = testScope.runTest {
        val launcherWithAwait = createLauncherWithAwait()
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")

        launcherWithAwait.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        conn.simulateConnectionFailed()
        testScheduler.runCurrent()
        conn.simulateConnected()
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(7_001.milliseconds)
        testScheduler.runCurrent()

        assertEquals(
            "every start() must be matched by exactly one stop()",
            fakeSessionSource.startCount,
            fakeSessionSource.stopCount
        )
    }

    @Test
    fun reentrantRelaunchFromTheCallbackSurvivesTheOuterCleanup() = testScope.runTest {
        val first = fakeBrowserFactory.getOrCreate("com.spotify.music")
        val second = fakeBrowserFactory.getOrCreate("org.videolan.vlc")

        launcher.launchAndPlay("com.spotify.music") {
            launcher.launchAndPlay("org.videolan.vlc")
        }
        testScheduler.runCurrent()

        first.simulateConnected()
        testScheduler.runCurrent()
        first.simulatePlaybackState(3) // STATE_PLAYING -> finishAttempt(success = true)
        testScheduler.advanceTimeBy(1_500.milliseconds)
        testScheduler.runCurrent()

        assertFalse("The relaunched attempt must keep its connection", second.isDisconnected)
        assertEquals(LauncherState.CONNECTING, launcher.launcherState.value)
    }

    @Test
    fun resetNotifiesThePendingCallbackWithFailure() = testScope.runTest {
        var completed: Boolean? = null

        launcher.launchAndPlay("com.spotify.music") { completed = it }
        testScheduler.runCurrent()

        launcher.reset()
        testScheduler.runCurrent()

        assertEquals(false, completed)
    }

    @Test
    fun usesTheRootReportedByTheConnectionWhenBrowserReadyDoesNotCarryOne() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        conn.rootId = "spotify-root"
        conn.rootItems = listOf(MediaBrowserItem("track-9", isPlayable = true, isBrowsable = false))

        launcher.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        conn.simulateConnected(BrowserReady(rootId = "root"))
        testScheduler.runCurrent()

        assertEquals("track-9", conn.lastPlayedMediaId)
        assertTrue(
            "Must subscribe to and unsubscribe from the connection's own root",
            conn.unsubscribedCategories.contains("spotify-root")
        )
    }

    @Test
    fun debounceStateIsVisibleSynchronouslyEvenThoughTheAttemptIsConfined() = testScope.runTest {
        launcher.launchAndPlay("com.spotify.music")

        // Without runCurrent(): the next press must already see CONNECTING.
        assertEquals(LauncherState.CONNECTING, launcher.launcherState.value)
        assertFalse("The attempt body must not have run on the caller thread", fakeWakeLock.isHeld)

        testScheduler.runCurrent()
        assertTrue("The confined body acquires the WakeLock", fakeWakeLock.isHeld)
    }

    @Test
    fun neverBroadcastsAMediaButtonToTheTargetPackage() = testScope.runTest {
        val launcherWithAwait = createLauncherWithAwait()
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")

        launcherWithAwait.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        conn.simulateConnectionFailed()
        testScheduler.runCurrent()

        assertEquals("com.spotify.music", fakeAppOpener.lastOpenedPackage)
        assertFalse(
            "A directed ACTION_MEDIA_BUTTON can crash the player on Android 12+",
            fakeAudioKeyDispatcher.dispatchedPlayKey
        )
        // Directed channel is sessionSource.play(), not a broadcast
        assertEquals(1, fakeSessionSource.startCount)
    }

    @Test
    fun awaitSessionTimeoutEscalatesToLevel3InsteadOfClosingAttempt() = testScope.runTest {
        val launcherWithAwait = createLauncherWithAwait()
        fakeAudioKeyDispatcher.lastActivePackage = "com.spotify.music"
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")

        var completedResult: Boolean? = null
        launcherWithAwait.launchAndPlay("com.spotify.music") { completedResult = it }
        testScheduler.runCurrent()

        // Level 2 fails -> escalates to Level 2.5 openApp -> awaitSession
        conn.simulateConnectionFailed()
        testScheduler.runCurrent()

        assertEquals("com.spotify.music", fakeAppOpener.lastOpenedPackage)
        assertFalse("Level 3 should not be dispatched yet", fakeAudioKeyDispatcher.dispatchedPlayKey)
        assertNull("Attempt should still be running", completedResult)

        // Advance past awaitSession watchdog (7000ms)
        testScheduler.advanceTimeBy(7.seconds)
        testScheduler.runCurrent()

        // Level 3 should have been executed upon awaitSession timeout
        assertTrue("Level 3 play key must be dispatched after awaitSession timeout", fakeAudioKeyDispatcher.dispatchedPlayKey)
        assertEquals(false, completedResult)
    }

    @Test
    fun awaitSessionDispatchesSecondPlayWhenProgressingFromNoneToPaused() = testScope.runTest {
        val launcherWithAwait = createLauncherWithAwait()
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")

        launcherWithAwait.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()

        // Level 2 fails -> escalate to 2.5
        conn.simulateConnectionFailed()
        testScheduler.runCurrent()

        // Session appears initially in NONE
        fakeSessionSource.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata(),
                status = PlaybackStatus.NONE
            )
        )
        testScheduler.runCurrent()
        assertEquals(1, fakeSessionSource.playCount)

        // Session progresses to PAUSED
        fakeSessionSource.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata("Song", "Artist", "Album", 1000L),
                status = PlaybackStatus.PAUSED
            )
        )
        testScheduler.runCurrent()
        assertEquals("Should dispatch second play when transitioning from NONE to PAUSED", 2, fakeSessionSource.playCount)
    }

    @Test
    fun playingStateRevertingToBufferingWithinWindowDoesNotFinishAttempt() = testScope.runTest {
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        var completedResult: Boolean? = null

        launcher.launchAndPlay("com.spotify.music") { completedResult = it }
        testScheduler.runCurrent()

        conn.simulateConnected()
        testScheduler.runCurrent()

        // Announce STATE_PLAYING (3)
        conn.simulatePlaybackState(3)
        testScheduler.runCurrent()

        // Advance 500ms (within the 1500ms confirmation window)
        testScheduler.advanceTimeBy(500.milliseconds)
        testScheduler.runCurrent()

        assertNull("Attempt must not finish immediately upon receiving PLAYING", completedResult)
        assertFalse("Browser must not be disconnected during confirmation window", conn.isDisconnected)

        // Reverts to STATE_BUFFERING (6)
        conn.simulatePlaybackState(6)
        testScheduler.runCurrent()

        // Advance past original 1500ms window
        testScheduler.advanceTimeBy(1500.milliseconds)
        testScheduler.runCurrent()

        assertNull("Attempt must not finish as successful after reverting to BUFFERING", completedResult)
        assertEquals(LauncherState.BUFFERING, launcher.launcherState.value)
    }

    /**
     * The browser is disconnected on escalation, but the platform posts that disconnect, so
     * callbacks already queued still arrive. Once the attempt has left the browser phases, the
     * session wait is the only authority: a late connect must not dispatch a second play into the
     * player that was just opened, and a late buffer must not rearm the browser's watchdog.
     */
    @Test
    fun aBrowserCallbackArrivingAfterEscalationIsIgnored() = testScope.runTest {
        val escalating = createLauncherWithAwait()
        val conn = fakeBrowserFactory.getOrCreate("com.spotify.music")
        conn.rootItems = listOf(MediaBrowserItem("track-1", isPlayable = true, isBrowsable = false))

        escalating.launchAndPlay("com.spotify.music")
        testScheduler.runCurrent()
        conn.simulateConnectionFailed()
        testScheduler.runCurrent()
        assertEquals(8_000L, fakeWakeLock.lastTimeoutMs)

        conn.simulateConnected()
        conn.simulatePlaybackState(6) // STATE_BUFFERING
        testScheduler.runCurrent()

        assertNull("A stale connect must not play through the dead browser", conn.lastPlayedMediaId)
        assertFalse(conn.genericPlayCalled)
        assertEquals(LauncherState.CONNECTING, escalating.launcherState.value)
        assertEquals("A stale buffer must not renew the WakeLock", 8_000L, fakeWakeLock.lastTimeoutMs)
    }
}

