package com.opensab.app.source.music

import com.opensab.domain.music.MediaAppInfo
import com.opensab.domain.music.MediaSessionSnapshot
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.TrackMetadata
import com.opensab.domain.music.VolumeLevel
import com.opensab.domain.music.VolumeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SystemMediaControllerAdapterTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun publishesActiveSessionMetadataAndStatus() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val adapter = SystemMediaControllerAdapter(sessions, FakeVolumeSource())
        adapter.start(backgroundScope)

        sessions.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata("Yesterday", "The Beatles", "Help", 125_000L),
                status = PlaybackStatus.PLAYING
            )
        )

        val state = adapter.musicState.value
        assertEquals("Yesterday", state.metadata.title)
        assertEquals("The Beatles", state.metadata.artist)
        assertEquals("Help", state.metadata.album)
        assertEquals(125_000L, state.metadata.durationMs)
        assertEquals(PlaybackStatus.PLAYING, state.status)
    }

    @Test
    fun clearsMetadataWhenNoSessionIsActive() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val adapter = SystemMediaControllerAdapter(sessions, FakeVolumeSource())
        adapter.start(backgroundScope)

        sessions.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata("Yesterday", "The Beatles"),
                status = PlaybackStatus.PLAYING
            )
        )
        sessions.emit(null)

        val state = adapter.musicState.value
        assertEquals(TrackMetadata(), state.metadata)
        assertEquals(PlaybackStatus.NONE, state.status)
    }

    @Test
    fun publishesVolumeAndMuteFromVolumeSource() = testScope.runTest {
        val volume = FakeVolumeSource()
        val adapter = SystemMediaControllerAdapter(FakeMediaSessionSource(), volume)
        adapter.start(backgroundScope)

        volume.emit(VolumeLevel(percent = 40, isMuted = true))

        assertEquals(40, adapter.musicState.value.volumePercent)
        assertTrue(adapter.musicState.value.isMuted)

        volume.emit(VolumeLevel(percent = 55, isMuted = false))

        assertEquals(55, adapter.musicState.value.volumePercent)
        assertFalse(adapter.musicState.value.isMuted)
    }

    @Test
    fun delegatesTransportCommandsToSession() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val adapter = SystemMediaControllerAdapter(sessions, FakeVolumeSource())
        adapter.start(backgroundScope)

        adapter.play()
        adapter.pause()
        adapter.skipToNext()
        adapter.skipToPrevious()

        assertEquals(1, sessions.playCount)
        assertEquals(1, sessions.pauseCount)
        assertEquals(1, sessions.skipNextCount)
        assertEquals(1, sessions.skipPrevCount)
    }

    @Test
    fun togglePlayPausePausesWhilePlaying() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val adapter = SystemMediaControllerAdapter(sessions, FakeVolumeSource())
        adapter.start(backgroundScope)

        sessions.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata("Yesterday", "The Beatles"),
                status = PlaybackStatus.PLAYING
            )
        )
        adapter.togglePlayPause()

        assertEquals(1, sessions.pauseCount)
        assertEquals(0, sessions.playCount)
    }

    @Test
    fun togglePlayPausePlaysWhenNotPlaying() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val adapter = SystemMediaControllerAdapter(sessions, FakeVolumeSource())
        adapter.start(backgroundScope)

        // No session at all: the handlebar still asks the last player to resume.
        adapter.togglePlayPause()
        assertEquals(1, sessions.playCount)

        listOf(PlaybackStatus.PAUSED, PlaybackStatus.STOPPED, PlaybackStatus.NONE).forEach { status ->
            sessions.emit(
                MediaSessionSnapshot(
                    packageName = "com.spotify.music",
                    metadata = TrackMetadata("Yesterday", "The Beatles"),
                    status = status
                )
            )
            adapter.togglePlayPause()
        }

        assertEquals(4, sessions.playCount)
        assertEquals(0, sessions.pauseCount)
    }

    @Test
    fun delegatesVolumeCommands() = testScope.runTest {
        val volume = FakeVolumeSource()
        val adapter = SystemMediaControllerAdapter(FakeMediaSessionSource(), volume)
        adapter.start(backgroundScope)

        adapter.volumeUp()
        adapter.volumeUp()
        adapter.volumeDown()
        adapter.toggleMute()

        assertEquals(2, volume.raiseCount)
        assertEquals(1, volume.lowerCount)
        assertEquals(1, volume.muteToggleCount)
    }

    @Test
    fun startAndStopPropagateToBothSources() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val volume = FakeVolumeSource()
        val adapter = SystemMediaControllerAdapter(sessions, volume)

        adapter.start(backgroundScope)
        assertEquals(1, sessions.startCount)
        assertEquals(1, volume.startCount)

        adapter.stop()
        assertEquals(1, sessions.stopCount)
        assertEquals(1, volume.stopCount)

        // Once stopped the adapter no longer mirrors the system: the last frame stays frozen.
        sessions.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata("Something", "George Harrison"),
                status = PlaybackStatus.PLAYING
            )
        )
        assertEquals(TrackMetadata(), adapter.musicState.value.metadata)
    }

    @Test
    fun restartingReplacesTheCollectorsInsteadOfDuplicatingThem() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val volume = FakeVolumeSource()
        val adapter = SystemMediaControllerAdapter(sessions, volume)

        adapter.start(backgroundScope)
        adapter.start(backgroundScope)

        assertEquals(1, sessions.stopCount)
        assertEquals(2, sessions.startCount)
        assertEquals(1, volume.stopCount)
        assertEquals(2, volume.startCount)
    }

    @Test
    fun launchesFavoritePlayerWhenNoSessionIsActiveAndPlayIsRequested() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val volume = FakeVolumeSource()
        val favRepo = InMemoryFavoriteMediaRepository("com.spotify.music")
        val launcher = FakeMediaAppLauncher()
        val scanner = FakeMediaAppScanner(
            listOf(
                MediaAppInfo("com.spotify.music", "Spotify", "com.spotify.music.service.MediaBrowserService")
            )
        )

        val adapter = SystemMediaControllerAdapter(
            sessionSource = sessions,
            volumeSource = volume,
            favoriteRepository = favRepo,
            appLauncher = launcher,
            appScanner = scanner
        )
        adapter.start(backgroundScope)

        adapter.play()

        assertEquals("com.spotify.music", launcher.lastLaunchedPackage)
        assertEquals("com.spotify.music.service.MediaBrowserService", launcher.lastServiceClassName)
        assertEquals(1, launcher.launchCount)
        assertEquals(0, sessions.playCount)
    }

    @Test
    fun escalatesToLevel2WhenActiveSessionDoesNotMatchConfiguredFavorite() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val favRepo = InMemoryFavoriteMediaRepository("com.spotify.music")
        val launcher = FakeMediaAppLauncher()
        val scanner = FakeMediaAppScanner(
            listOf(MediaAppInfo("com.spotify.music", "Spotify", "com.spotify.music.service.MediaBrowserService"))
        )

        val adapter = SystemMediaControllerAdapter(
            sessionSource = sessions,
            volumeSource = FakeVolumeSource(),
            favoriteRepository = favRepo,
            appLauncher = launcher,
            appScanner = scanner
        )
        adapter.start(backgroundScope)

        // Ignore active foreign session when favorite is configured
        sessions.emit(
            MediaSessionSnapshot(
                packageName = "com.google.android.apps.youtube.music",
                metadata = TrackMetadata("Song", "Artist"),
                status = PlaybackStatus.PAUSED
            )
        )

        adapter.play()

        assertEquals(1, launcher.launchCount)
        assertEquals("com.spotify.music", launcher.lastLaunchedPackage)
        assertEquals(0, sessions.playCount)
    }

    @Test
    fun discardsZombieSessionWithStatusNoneAndEscalatesToLauncher() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val volume = FakeVolumeSource()
        val favRepo = InMemoryFavoriteMediaRepository("com.spotify.music")
        val launcher = FakeMediaAppLauncher()

        val adapter = SystemMediaControllerAdapter(
            sessionSource = sessions,
            volumeSource = volume,
            favoriteRepository = favRepo,
            appLauncher = launcher,
            appScanner = FakeMediaAppScanner(listOf(MediaAppInfo("com.spotify.music", "Spotify", "com.spotify.Service")))
        )
        adapter.start(backgroundScope)

        sessions.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata(),
                status = PlaybackStatus.NONE
            )
        )

        adapter.play()

        assertEquals(1, launcher.launchCount)
        assertEquals("com.spotify.music", launcher.lastLaunchedPackage)
        assertEquals(0, sessions.playCount)
    }

    @Test
    fun allowsPlayWithoutBikeConnectionUsingContinuousFavoriteRepo() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val favRepo = InMemoryFavoriteMediaRepository("com.spotify.music")
        val launcher = FakeMediaAppLauncher()

        val adapter = SystemMediaControllerAdapter(
            sessionSource = sessions,
            volumeSource = FakeVolumeSource(),
            favoriteRepository = favRepo,
            appLauncher = launcher,
            applicationScope = backgroundScope
        )

        // Trigger play() without active BLE connection
        adapter.play()

        assertEquals(1, launcher.launchCount)
        assertEquals("com.spotify.music", launcher.lastLaunchedPackage)
    }

    @Test
    fun ignoresPlayPressWhenLauncherIsConnectingOrBuffering() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val favRepo = InMemoryFavoriteMediaRepository("com.spotify.music")
        val launcher = FakeMediaAppLauncher()

        val adapter = SystemMediaControllerAdapter(
            sessionSource = sessions,
            volumeSource = FakeVolumeSource(),
            favoriteRepository = favRepo,
            appLauncher = launcher
        )
        adapter.start(backgroundScope)

        launcher.emitState(LauncherState.CONNECTING)
        adapter.play()
        assertEquals(0, launcher.launchCount)

        launcher.emitState(LauncherState.BUFFERING)
        adapter.play()
        assertEquals(0, launcher.launchCount)

        launcher.emitState(LauncherState.IDLE)
        adapter.play()
        assertEquals(1, launcher.launchCount)
    }

    @Test
    fun secondPlayWhileTheFirstColdStartIsInFlightLaunchesOnce() = testScope.runTest {
        val launcher = FakeMediaAppLauncher(connectsOnLaunch = true)
        val adapter = SystemMediaControllerAdapter(
            sessionSource = FakeMediaSessionSource(),
            volumeSource = FakeVolumeSource(),
            favoriteRepository = InMemoryFavoriteMediaRepository("com.spotify.music"),
            appLauncher = launcher
        )
        adapter.start(backgroundScope)

        adapter.play()
        adapter.play()

        assertEquals(1, launcher.launchCount)
    }

    @Test
    fun twoPlaysQueuedWhilePreferencesLoadLaunchOnce() = testScope.runTest {
        val launcher = FakeMediaAppLauncher(connectsOnLaunch = true)
        val late = MutableSharedFlow<String?>()
        val adapter = SystemMediaControllerAdapter(
            sessionSource = FakeMediaSessionSource(),
            volumeSource = FakeVolumeSource(),
            favoriteRepository = object : FavoriteMediaRepository {
                override val favoritePackage: Flow<String?> = late
                override suspend fun setFavoritePackage(packageName: String?) = Unit
            },
            appLauncher = launcher
        )
        adapter.start(backgroundScope)

        adapter.play()
        adapter.play()
        late.emit("com.spotify.music")
        advanceTimeBy(100.milliseconds)
        runCurrent()

        assertEquals(1, launcher.launchCount)
    }

    @Test
    fun neverBlocksVolumeOrMuteWhenLauncherIsConnecting() = testScope.runTest {
        val volume = FakeVolumeSource()
        val launcher = FakeMediaAppLauncher()

        val adapter = SystemMediaControllerAdapter(
            sessionSource = FakeMediaSessionSource(),
            volumeSource = volume,
            appLauncher = launcher
        )
        adapter.start(backgroundScope)

        launcher.emitState(LauncherState.CONNECTING)
        adapter.volumeUp()
        adapter.volumeDown()
        adapter.toggleMute()

        assertEquals(1, volume.raiseCount)
        assertEquals(1, volume.lowerCount)
        assertEquals(1, volume.muteToggleCount)
    }

    @Test
    fun escalatesToLevel2WhenPausedSessionFailsToResumeWithinDeadline() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val favRepo = InMemoryFavoriteMediaRepository("com.spotify.music")
        val launcher = FakeMediaAppLauncher()

        val adapter = SystemMediaControllerAdapter(
            sessionSource = sessions,
            volumeSource = FakeVolumeSource(),
            favoriteRepository = favRepo,
            appLauncher = launcher,
            applicationScope = backgroundScope
        )
        adapter.start(backgroundScope)

        sessions.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata("Song", "Artist"),
                status = PlaybackStatus.PAUSED
            )
        )

        adapter.play()
        assertEquals(1, sessions.playCount)
        assertEquals(0, launcher.launchCount)

        // Advance beyond the 2s Level 1 watchdog
        testScheduler.advanceTimeBy(2001.milliseconds)
        testScheduler.runCurrent()

        // Session failed to transition to PLAYING: must escalate to Level 2
        assertEquals(1, launcher.launchCount)
        assertEquals("com.spotify.music", launcher.lastLaunchedPackage)
    }

    @Test
    fun doesNotEscalateToLevel2WhenPausedSessionSuccessfullyResumesToPlaying() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val favRepo = InMemoryFavoriteMediaRepository("com.spotify.music")
        val launcher = FakeMediaAppLauncher()

        val adapter = SystemMediaControllerAdapter(
            sessionSource = sessions,
            volumeSource = FakeVolumeSource(),
            favoriteRepository = favRepo,
            appLauncher = launcher,
            applicationScope = backgroundScope
        )
        adapter.start(backgroundScope)

        sessions.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata("Song", "Artist"),
                status = PlaybackStatus.PAUSED
            )
        )

        adapter.play()
        assertEquals(1, sessions.playCount)

        // Session resumes to PLAYING within deadline
        testScheduler.advanceTimeBy(500.milliseconds)
        sessions.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata("Song", "Artist"),
                status = PlaybackStatus.PLAYING
            )
        )

        testScheduler.advanceTimeBy(2.seconds)
        testScheduler.runCurrent()

        // Resumed successfully: no escalation
        assertEquals(0, launcher.launchCount)
    }

    @Test
    fun pauseWithinTheLevel1DeadlineCancelsTheEscalation() = testScope.runTest {
        // Field, 2026-09-25 21:00:26: play resumed, the rider paused 1.6 s later, and the pending
        // watchdog read the pause as a failed resume and cold-started the music again.
        val sessions = FakeMediaSessionSource()
        val favRepo = InMemoryFavoriteMediaRepository("com.aimp.player")
        val launcher = FakeMediaAppLauncher()

        val adapter = SystemMediaControllerAdapter(
            sessionSource = sessions,
            volumeSource = FakeVolumeSource(),
            favoriteRepository = favRepo,
            appLauncher = launcher,
            applicationScope = backgroundScope
        )
        adapter.start(backgroundScope)

        val paused = MediaSessionSnapshot(
            packageName = "com.aimp.player",
            metadata = TrackMetadata("Song", "Artist"),
            status = PlaybackStatus.PAUSED
        )
        sessions.emit(paused)

        adapter.play()
        testScheduler.advanceTimeBy(150.milliseconds)
        sessions.emit(paused.copy(status = PlaybackStatus.PLAYING))

        testScheduler.advanceTimeBy(1450.milliseconds)
        adapter.pause()
        sessions.emit(paused)

        testScheduler.advanceTimeBy(2001.milliseconds)
        testScheduler.runCurrent()

        assertEquals("The rider's pause must stand", 0, launcher.launchCount)
    }

    @Test
    fun setsPreferredPackageOnSessionSourceWhenFavoriteIsConfigured() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val favRepo = InMemoryFavoriteMediaRepository("app.morphe.android.apps.youtube.music")

        val adapter = SystemMediaControllerAdapter(
            sessionSource = sessions,
            volumeSource = FakeVolumeSource(),
            favoriteRepository = favRepo,
            applicationScope = backgroundScope
        )
        adapter.start(backgroundScope)

        assertEquals("app.morphe.android.apps.youtube.music", sessions.preferredPackage)
    }

    @Test
    fun escalatesFromLevel1EvenAfterAPreviousColdStartSucceeded() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val favRepo = InMemoryFavoriteMediaRepository("com.spotify.music")
        val launcher = FakeMediaAppLauncher()

        val adapter = SystemMediaControllerAdapter(
            sessionSource = sessions,
            volumeSource = FakeVolumeSource(),
            favoriteRepository = favRepo,
            appLauncher = launcher,
            applicationScope = backgroundScope
        )
        adapter.start(backgroundScope)

        // Launcher remained in ACTIVE from previous attempt
        launcher.emitState(LauncherState.ACTIVE)

        sessions.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata("Song", "Artist"),
                status = PlaybackStatus.PAUSED
            )
        )

        adapter.play()
        assertEquals(1, sessions.playCount)

        testScheduler.advanceTimeBy(2001.milliseconds)
        testScheduler.runCurrent()

        assertEquals(
            "ACTIVE is not busy: a zombie session must still escalate to Level 2",
            1,
            launcher.launchCount
        )
    }

    @Test
    fun repeatedPressesScheduleASingleLevel1Escalation() = testScope.runTest {
        val sessions = FakeMediaSessionSource()
        val favRepo = InMemoryFavoriteMediaRepository("com.spotify.music")
        val launcher = FakeMediaAppLauncher()

        val adapter = SystemMediaControllerAdapter(
            sessionSource = sessions,
            volumeSource = FakeVolumeSource(),
            favoriteRepository = favRepo,
            appLauncher = launcher,
            applicationScope = backgroundScope
        )
        adapter.start(backgroundScope)

        sessions.emit(
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                metadata = TrackMetadata("Song", "Artist"),
                status = PlaybackStatus.PAUSED
            )
        )

        // Debounce rapid successive presses (300ms)
        adapter.play()
        testScheduler.advanceTimeBy(300.milliseconds)
        adapter.play()
        testScheduler.advanceTimeBy(300.milliseconds)
        adapter.play()

        testScheduler.advanceTimeBy(2001.milliseconds)
        testScheduler.runCurrent()

        assertEquals("Only one cold start may be fired", 1, launcher.launchCount)
    }

    @Test
    fun resetDelegatesToAppLauncherReset() {
        val sessions = FakeMediaSessionSource()
        val launcher = FakeMediaAppLauncher()
        val adapter = SystemMediaControllerAdapter(
            sessionSource = sessions,
            volumeSource = FakeVolumeSource(),
            appLauncher = launcher
        )

        adapter.reset()

        assertEquals(1, launcher.resetCount)
    }

    private class FakeVolumeSource : VolumeSource {

        private val _level = MutableStateFlow(VolumeLevel())
        override val level: StateFlow<VolumeLevel> = _level.asStateFlow()

        var startCount = 0
            private set
        var stopCount = 0
            private set
        var raiseCount = 0
            private set
        var lowerCount = 0
            private set
        var muteToggleCount = 0
            private set

        fun emit(level: VolumeLevel) {
            _level.value = level
        }

        override fun start() {
            startCount++
        }

        override fun stop() {
            stopCount++
        }

        override fun raise() {
            raiseCount++
        }

        override fun lower() {
            lowerCount++
        }

        override fun toggleMute() {
            muteToggleCount++
        }
    }
}
