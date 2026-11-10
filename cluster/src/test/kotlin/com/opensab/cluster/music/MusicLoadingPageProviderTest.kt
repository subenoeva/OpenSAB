package com.opensab.cluster.music
import app.cash.turbine.test
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.StubPageProvider
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.RecordingMusicManager
import com.opensab.domain.music.TrackMetadata
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MusicLoadingPageProviderTest {

    private val musicState = MutableStateFlow(MusicState())
    private val livePage = StubPageProvider("LiveMusic", ProjectionFrame.RequestSabMode(1))
    private val errorPage = StubPageProvider("ErrorMusic", ProjectionFrame.RequestSabMode(2))
    private val musicManager = RecordingMusicManager()

    private fun createProvider(
        timeout: Duration = 8.seconds,
        volumeControl: StateFlow<Boolean> = volumeControlOn
    ): MusicLoadingPageProvider = MusicLoadingPageProvider(
        musicState = musicState,
        copy = englishClusterText,
        volumeControl = volumeControl,
        livePageFactory = { livePage },
        errorPageFactory = { errorPage },
        musicManager = musicManager,
        timeoutDuration = timeout
    )

    @Test
    fun renderProducesMusicLoadingFrameWithHeaderAndIcon() {
        val provider = createProvider()
        val frame = provider.render(requestId = 42)

        assertTrue(frame is ProjectionFrame.LiveScreen)
        val liveScreen = frame as ProjectionFrame.LiveScreen
        assertEquals("Loading", liveScreen.headerText)
        assertEquals(IconId.LOADING, liveScreen.contentIconId)
        assertEquals("", liveScreen.contentTextLine1)
        assertEquals("", liveScreen.contentTextLine2)
    }

    @Test
    fun mirrorExposesRequiredSlotsWithoutCollision() {
        val provider = createProvider()
        val mirror = provider.mirror()

        val left = mirror.actions.find { it.slot == MirrorSlot.LEFT }
        assertNotNull(left)
        assertEquals(MirrorActionToken.GO_BACK, left?.token)
        assertEquals(FunctionId.BACK, left?.functionId)

        val header = mirror.actions.find { it.slot == MirrorSlot.HEADER }
        assertNotNull(header)
        assertEquals(MirrorActionToken.OPEN_ROOT_MENU, header?.token)
        assertEquals(FunctionId.MENU, header?.functionId)

        val right = mirror.actions.find { it.slot == MirrorSlot.RIGHT }
        assertNotNull(right)
        assertEquals(MirrorActionToken.VOLUME_MUTE, right?.token)
        assertEquals(FunctionId.VOLUME_MUTE, right?.functionId)

        val up = mirror.actions.find { it.slot == MirrorSlot.UP }
        assertNotNull(up)
        assertEquals(MirrorActionToken.VOLUME_UP, up?.token)

        val down = mirror.actions.find { it.slot == MirrorSlot.DOWN }
        assertNotNull(down)
        assertEquals(MirrorActionToken.VOLUME_DOWN, down?.token)

        // Ensure every action occupies a distinct slot (no collisions)
        val slots = mirror.actions.map { it.slot }
        assertEquals(slots.size, slots.distinct().size)
    }

    @Test
    fun onInputBackReturnsPop() {
        val provider = createProvider()
        val action = provider.onInput(FunctionId.BACK)
        assertEquals(NavigationAction.Pop, action)
    }

    @Test
    fun onInputMenuReturnsReset() {
        val provider = createProvider()
        val action = provider.onInput(FunctionId.MENU)
        assertEquals(NavigationAction.Reset, action)
    }

    @Test
    fun volumeKeysLeaveTheVolumeAloneWhileHandlebarVolumeControlIsOff() {
        val provider = createProvider(volumeControl = MutableStateFlow(false))

        listOf(FunctionId.PREVIOUS, FunctionId.VOLUME_UP, FunctionId.NEXT, FunctionId.VOLUME_DOWN)
            .forEach { assertEquals(NavigationAction.None, provider.onInput(it)) }

        assertEquals(0, musicManager.volUpCalls + musicManager.volDownCalls)
    }

    @Test
    fun muteStillWorksWhileHandlebarVolumeControlIsOff() {
        val provider = createProvider(volumeControl = MutableStateFlow(false))

        provider.onInput(FunctionId.VOLUME_MUTE)

        assertEquals(1, musicManager.muteCalls)
    }

    @Test
    fun turningHandlebarVolumeControlBackOnTakesEffectOnTheNextPress() {
        val control = MutableStateFlow(false)
        val provider = createProvider(volumeControl = control)

        provider.onInput(FunctionId.VOLUME_UP)
        control.value = true
        provider.onInput(FunctionId.VOLUME_UP)

        assertEquals(1, musicManager.volUpCalls)
    }

    @Test
    fun onInputVolumeKeysDelegateToMusicManager() {
        val provider = createProvider()
        assertEquals(NavigationAction.None, provider.onInput(FunctionId.VOLUME_UP))
        assertEquals(1, musicManager.volUpCalls)

        assertEquals(NavigationAction.None, provider.onInput(FunctionId.VOLUME_DOWN))
        assertEquals(1, musicManager.volDownCalls)

        assertEquals(NavigationAction.None, provider.onInput(FunctionId.VOLUME_MUTE))
        assertEquals(1, musicManager.muteCalls)

        assertEquals(NavigationAction.None, provider.onInput(FunctionId.PREVIOUS))
        assertEquals(2, musicManager.volUpCalls)

        assertEquals(NavigationAction.None, provider.onInput(FunctionId.NEXT))
        assertEquals(2, musicManager.volDownCalls)

        // SELECT is ignored safely
        assertEquals(NavigationAction.None, provider.onInput(FunctionId.SELECT))
    }

    @Test
    fun workTriggersPlayAndTransitionsToLivePageWhenPlaying() = runTest {
        val provider = createProvider()

        var resultAction: NavigationAction? = null
        val job = launch {
            resultAction = provider.work()
        }

        runCurrent()
        // verify play() was called
        assertEquals(1, musicManager.totalCalls)

        // simulate playback starting
        musicState.value = MusicState(status = PlaybackStatus.PLAYING)
        runCurrent()

        assertTrue(resultAction is NavigationAction.Replace)
        assertSame(livePage, (resultAction as NavigationAction.Replace).page)
        job.cancel()
    }

    @Test
    fun workWaitsForPlayingEvenIfTitleIsPresent() = runTest {
        val provider = createProvider()

        var resultAction: NavigationAction? = null
        val job = launch {
            resultAction = provider.work()
        }

        runCurrent()
        // Stale or newly arrived metadata without active playback must not dismiss the loading page
        musicState.value = MusicState(
            status = PlaybackStatus.NONE,
            metadata = TrackMetadata(title = "Coldplay - Yellow")
        )
        runCurrent()
        assertNull(resultAction)

        // Only when playback actually starts does it transition to the live page
        musicState.value = MusicState(
            status = PlaybackStatus.PLAYING,
            metadata = TrackMetadata(title = "Coldplay - Yellow")
        )
        runCurrent()

        assertTrue(resultAction is NavigationAction.Replace)
        assertSame(livePage, (resultAction as NavigationAction.Replace).page)
        job.cancel()
    }

    @Test
    fun workTimesOutAfter8SecondsAndTransitionsToErrorPage() = runTest {
        val provider = createProvider(timeout = 8.seconds)

        var resultAction: NavigationAction? = null
        val job = launch {
            resultAction = provider.work()
        }

        runCurrent()
        advanceTimeBy(7999.milliseconds)
        runCurrent()
        assertEquals(null, resultAction)

        advanceTimeBy(2.milliseconds)
        runCurrent()

        assertTrue(resultAction is NavigationAction.Replace)
        assertSame(errorPage, (resultAction as NavigationAction.Replace).page)
        job.cancel()
    }

    @Test
    fun workCancellationCallsMusicManagerResetIfStillCold() = runTest {
        val provider = createProvider()

        val job = launch {
            provider.work()
        }

        runCurrent()
        assertEquals(0, musicManager.resetCalls)

        // Rider presses BACK or MENU -> job cancelled
        job.cancel()
        runCurrent()

        assertEquals(1, musicManager.resetCalls)
    }

    @Test
    fun workCancellationDoesNotCallResetIfAlreadyPlaying() = runTest {
        val provider = createProvider()

        val job = launch {
            provider.work()
        }

        runCurrent()
        musicState.value = MusicState(status = PlaybackStatus.PLAYING)
        runCurrent()

        job.cancel()
        runCurrent()

        assertEquals(0, musicManager.resetCalls)
    }

    @Test
    fun workCancellationCallsMusicManagerResetEvenIfStaleTitlePresent() = runTest {
        val provider = createProvider()
        musicState.value = MusicState(
            status = PlaybackStatus.NONE,
            metadata = TrackMetadata(title = "Stale Cached Title")
        )

        val job = launch {
            provider.work()
        }

        runCurrent()
        assertEquals(0, musicManager.resetCalls)

        job.cancel()
        runCurrent()

        assertEquals(1, musicManager.resetCalls)
    }

    @Test
    fun speechEmitsMusicLoadingOnceOnMount() = runTest {
        val provider = createProvider()
        provider.speech.test {
            assertEquals(listOf(SpeechRequest(SpeechToken.MUSIC_LOADING)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
