package com.opensab.cluster.music

import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.music.DefaultMusicManager
import com.opensab.domain.music.MockMediaControllerAdapter
import com.opensab.domain.music.MusicManager
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.RecordingMusicManager
import com.opensab.domain.music.TrackMetadata
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that handlebar button presses correctly control music playback through the
 * `MusicPageProvider`, which translates input events into control commands and renders
 * the resulting music state on the cluster's LiveScreen projection frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicPageProviderInputTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun handlebarButtonPressesControlMusicPlayback() = testScope.runTest {
        val mediaAdapter = MockMediaControllerAdapter()

        val musicManager: MusicManager = DefaultMusicManager(mediaAdapter)
        val musicPage = MusicPageProvider(musicManager.currentState, englishClusterText, volumeControlOn, musicManager)

        // Set active track
        mediaAdapter.setTrack(TrackMetadata("Yesterday", "The Beatles"))

        // BACK toggles play/pause
        val backAction = musicPage.onInput(FunctionId.BACK)
        assertEquals(com.opensab.cluster.NavigationAction.None, backAction)
        assertEquals(PlaybackStatus.PLAYING, mediaAdapter.musicState.value.status)

        // SELECT skips track
        val selectAction = musicPage.onInput(FunctionId.SELECT)
        assertEquals(com.opensab.cluster.NavigationAction.None, selectAction)
        assertEquals(1, mediaAdapter.skipNextCount)

        // PREVIOUS increases volume
        val prevAction = musicPage.onInput(FunctionId.PREVIOUS)
        assertEquals(com.opensab.cluster.NavigationAction.None, prevAction)

        // NEXT decreases volume
        val nextAction = musicPage.onInput(FunctionId.NEXT)
        assertEquals(com.opensab.cluster.NavigationAction.None, nextAction)

        // Verify musicPage renders the music track on LiveScreen
        val frame = musicPage.render(requestId = 1)
        assertTrue(frame is ProjectionFrame.LiveScreen)
        assertEquals("Yesterday", (frame as ProjectionFrame.LiveScreen).contentTextLine1)
        assertEquals("The Beatles", frame.contentTextLine2)
    }

    @Test
    fun handlebarVolumeKeysDoNothingWhileHandlebarVolumeControlIsOff() = testScope.runTest {
        val musicManager = RecordingMusicManager()
        val musicPage = MusicPageProvider(musicManager.currentState, englishClusterText, MutableStateFlow(false), musicManager)

        listOf(FunctionId.PREVIOUS, FunctionId.VOLUME_UP, FunctionId.NEXT, FunctionId.VOLUME_DOWN)
            .forEach { musicPage.onInput(it) }

        assertEquals(0, musicManager.volUpCalls + musicManager.volDownCalls)
    }

    @Test
    fun turningHandlebarVolumeControlBackOnTakesEffectOnTheNextPress() = testScope.runTest {
        val musicManager = RecordingMusicManager()
        val control = MutableStateFlow(false)
        val musicPage = MusicPageProvider(musicManager.currentState, englishClusterText, control, musicManager)

        musicPage.onInput(FunctionId.VOLUME_UP)
        control.value = true
        musicPage.onInput(FunctionId.VOLUME_UP)

        assertEquals(1, musicManager.volUpCalls)
    }

    @Test
    fun muteStillTogglesWhileHandlebarVolumeControlIsOff() = testScope.runTest {
        val musicManager = RecordingMusicManager()
        val musicPage = MusicPageProvider(musicManager.currentState, englishClusterText, MutableStateFlow(false), musicManager)

        musicPage.onInput(FunctionId.VOLUME_MUTE)

        assertEquals(1, musicManager.muteCalls)
    }
}
