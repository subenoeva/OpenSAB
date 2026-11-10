package com.opensab.cluster.music
import app.cash.turbine.test
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.music.RecordingMusicManager
import com.opensab.domain.music.RecordingVolumeSource
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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MusicErrorPageProviderTest {

    private val volumeSource = RecordingVolumeSource()
    private val musicManager = RecordingMusicManager()

    private fun createProvider(
        autoPopDuration: Duration = 5.seconds,
        volumeControl: StateFlow<Boolean> = volumeControlOn
    ): MusicErrorPageProvider = MusicErrorPageProvider(
        copy = englishClusterText,
        volumeControl = volumeControl,
        volumeSource = volumeSource,
        musicManager = musicManager,
        autoPopDuration = autoPopDuration
    )

    @Test
    fun renderProducesMusicErrorFrameWithHeaderAndCancelButton() {
        val provider = createProvider()
        val frame = provider.render(requestId = 42)

        assertTrue(frame is ProjectionFrame.LiveScreen)
        val liveScreen = frame as ProjectionFrame.LiveScreen
        assertEquals("App Error", liveScreen.headerText)
        assertEquals(IconId.DFU_WARNING, liveScreen.contentIconId)
        assertEquals(IconId.LIVE_CANCEL_ONE_BUTTON, liveScreen.leftActionContentIconId)
        assertEquals(IconId.LIVE_ARROW_LEFT, liveScreen.leftActionIconId)
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

        val slots = mirror.actions.map { it.slot }
        assertEquals(slots.size, slots.distinct().size)
    }

    @Test
    fun onInputBackReturnsPopImmediately() {
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

        assertEquals(0, volumeSource.raiseCalls + volumeSource.lowerCalls)
        assertEquals(0, musicManager.volUpCalls + musicManager.volDownCalls)
    }

    @Test
    fun muteStillWorksWhileHandlebarVolumeControlIsOff() {
        val provider = createProvider(volumeControl = MutableStateFlow(false))

        provider.onInput(FunctionId.VOLUME_MUTE)

        assertEquals(1, volumeSource.muteCalls)
    }

    @Test
    fun turningHandlebarVolumeControlBackOnTakesEffectOnTheNextPress() {
        val control = MutableStateFlow(false)
        val provider = createProvider(volumeControl = control)

        provider.onInput(FunctionId.VOLUME_UP)
        control.value = true
        provider.onInput(FunctionId.VOLUME_UP)

        assertEquals(1, volumeSource.raiseCalls)
    }

    @Test
    fun onInputVolumeAdjustsVolume() {
        val provider = createProvider()
        assertEquals(NavigationAction.None, provider.onInput(FunctionId.VOLUME_UP))
        assertEquals(1, volumeSource.raiseCalls)

        assertEquals(NavigationAction.None, provider.onInput(FunctionId.VOLUME_DOWN))
        assertEquals(1, volumeSource.lowerCalls)

        assertEquals(NavigationAction.None, provider.onInput(FunctionId.VOLUME_MUTE))
        assertEquals(1, volumeSource.muteCalls)

        assertEquals(NavigationAction.None, provider.onInput(FunctionId.PREVIOUS))
        assertEquals(2, volumeSource.raiseCalls)

        assertEquals(NavigationAction.None, provider.onInput(FunctionId.NEXT))
        assertEquals(2, volumeSource.lowerCalls)
    }

    @Test
    fun workPopsAutomaticallyAfter5Seconds() = runTest {
        val provider = createProvider(autoPopDuration = 5.seconds)

        var resultAction: NavigationAction? = null
        val job = launch {
            resultAction = provider.work()
        }

        runCurrent()
        advanceTimeBy(4999.milliseconds)
        runCurrent()
        assertEquals(null, resultAction)

        advanceTimeBy(2.milliseconds)
        runCurrent()

        assertEquals(NavigationAction.Pop, resultAction)
        job.cancel()
    }

    @Test
    fun speechEmitsMusicErrorOnceOnMount() = runTest {
        val provider = createProvider()
        provider.speech.test {
            assertEquals(listOf(SpeechRequest(SpeechToken.MUSIC_ERROR)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
