package com.opensab.cluster.music
import app.cash.turbine.test
import com.opensab.cluster.CuingPage
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.audio.Cue
import com.opensab.domain.music.RecordingVolumeSource
import com.opensab.domain.music.VolumeLevel
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechQuantity
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VolumePageProviderTest {

    @Test
    fun `renders volume percentage and discrete icon levels correctly`() {
        val volumeSource = RecordingVolumeSource()
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        // 0% -> LEVEL_0
        volumeSource.state.value = VolumeLevel(percent = 0, isMuted = false)
        var frame = provider.render(requestId = 1) as ProjectionFrame.LiveScreen
        assertEquals(IconId.VOLUME_LEVEL_0, frame.contentIconId)
        assertEquals("0%", frame.contentTextLine1)
        assertEquals(ColorId.GREY, frame.headerBgColorId)

        // 15% -> LEVEL_1
        volumeSource.state.value = VolumeLevel(percent = 15, isMuted = false)
        frame = provider.render(requestId = 2) as ProjectionFrame.LiveScreen
        assertEquals(IconId.VOLUME_LEVEL_1, frame.contentIconId)
        assertEquals("15%", frame.contentTextLine1)

        // 35% -> LEVEL_2
        volumeSource.state.value = VolumeLevel(percent = 35, isMuted = false)
        frame = provider.render(requestId = 3) as ProjectionFrame.LiveScreen
        assertEquals(IconId.VOLUME_LEVEL_2, frame.contentIconId)
        assertEquals("35%", frame.contentTextLine1)

        // 55% -> LEVEL_3
        volumeSource.state.value = VolumeLevel(percent = 55, isMuted = false)
        frame = provider.render(requestId = 4) as ProjectionFrame.LiveScreen
        assertEquals(IconId.VOLUME_LEVEL_3, frame.contentIconId)
        assertEquals("55%", frame.contentTextLine1)

        // 75% -> LEVEL_4
        volumeSource.state.value = VolumeLevel(percent = 75, isMuted = false)
        frame = provider.render(requestId = 5) as ProjectionFrame.LiveScreen
        assertEquals(IconId.VOLUME_LEVEL_4, frame.contentIconId)
        assertEquals("75%", frame.contentTextLine1)

        // 95% -> LEVEL_5
        volumeSource.state.value = VolumeLevel(percent = 95, isMuted = false)
        frame = provider.render(requestId = 6) as ProjectionFrame.LiveScreen
        assertEquals(IconId.VOLUME_LEVEL_5, frame.contentIconId)
        assertEquals("95%", frame.contentTextLine1)
    }

    @Test
    fun `renders mute state with LEVEL_0 and Mute text`() {
        val volumeSource = RecordingVolumeSource()
        volumeSource.state.value = VolumeLevel(percent = 80, isMuted = true)
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        val frame = provider.render(requestId = 10) as ProjectionFrame.LiveScreen
        assertEquals(IconId.VOLUME_LEVEL_0, frame.contentIconId)
        assertEquals("Mute", frame.contentTextLine1)
    }

    @Test
    fun `back button pops back to root menu`() {
        val volumeSource = RecordingVolumeSource()
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        val action = provider.onInput(FunctionId.BACK)
        assertEquals(NavigationAction.Pop, action)
    }

    @Test
    fun `menu button resets back to root menu`() {
        val volumeSource = RecordingVolumeSource()
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        val action = provider.onInput(FunctionId.MENU)
        assertEquals(NavigationAction.Reset, action)
    }

    @Test
    fun `previous and volume up buttons raise volume`() {
        val volumeSource = RecordingVolumeSource()
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        val prevAction = provider.onInput(FunctionId.PREVIOUS)
        assertEquals(NavigationAction.None, prevAction)
        assertEquals(1, volumeSource.raiseCalls)

        val volUpAction = provider.onInput(FunctionId.VOLUME_UP)
        assertEquals(NavigationAction.None, volUpAction)
        assertEquals(2, volumeSource.raiseCalls)
    }

    @Test
    fun `next and volume down buttons lower volume`() {
        val volumeSource = RecordingVolumeSource()
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        val nextAction = provider.onInput(FunctionId.NEXT)
        assertEquals(NavigationAction.None, nextAction)
        assertEquals(1, volumeSource.lowerCalls)

        val volDownAction = provider.onInput(FunctionId.VOLUME_DOWN)
        assertEquals(NavigationAction.None, volDownAction)
        assertEquals(2, volumeSource.lowerCalls)
    }

    @Test
    fun `volume mute button toggles mute`() {
        val volumeSource = RecordingVolumeSource()
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        val action = provider.onInput(FunctionId.VOLUME_MUTE)
        assertEquals(NavigationAction.None, action)
        assertEquals(1, volumeSource.muteCalls)
    }

    @Test
    fun `select button toggles mute`() {
        val volumeSource = RecordingVolumeSource()
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        val action = provider.onInput(FunctionId.SELECT)
        assertEquals(NavigationAction.None, action)
        assertEquals(1, volumeSource.muteCalls)
    }

    @Test
    fun `mirror declares the five zones of the volume page`() {
        val volumeSource = RecordingVolumeSource()
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        assertEquals(
            listOf(
                MirrorAction(
                    token = MirrorActionToken.VOLUME_UP,
                    functionId = FunctionId.PREVIOUS,
                    slot = MirrorSlot.UP,
                    aliases = setOf(FunctionId.VOLUME_UP)
                ),
                MirrorAction(
                    token = MirrorActionToken.VOLUME_DOWN,
                    functionId = FunctionId.NEXT,
                    slot = MirrorSlot.DOWN,
                    aliases = setOf(FunctionId.VOLUME_DOWN)
                ),
                MirrorAction(
                    token = MirrorActionToken.VOLUME_MUTE,
                    functionId = FunctionId.SELECT,
                    slot = MirrorSlot.PRIMARY,
                    aliases = setOf(FunctionId.VOLUME_MUTE)
                ),
                MirrorAction(
                    token = MirrorActionToken.GO_BACK,
                    functionId = FunctionId.BACK,
                    slot = MirrorSlot.LEFT
                ),
                MirrorAction(
                    token = MirrorActionToken.OPEN_ROOT_MENU,
                    functionId = FunctionId.MENU,
                    slot = MirrorSlot.HEADER
                )
            ),
            provider.mirror().actions
        )
    }

    @Test
    fun `mirror declares every zone enabled`() {
        val volumeSource = RecordingVolumeSource()
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        assertTrue(provider.mirror().actions.all { it.enabled })
    }

    @Test
    fun `mirror reaches every function the page acts on`() {
        val volumeSource = RecordingVolumeSource()
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        val reachable = provider.mirror().actions
            .flatMap { listOf(it.functionId) + it.aliases }
            .toSet()

        assertEquals(
            setOf(
                FunctionId.PREVIOUS,
                FunctionId.VOLUME_UP,
                FunctionId.NEXT,
                FunctionId.VOLUME_DOWN,
                FunctionId.SELECT,
                FunctionId.VOLUME_MUTE,
                FunctionId.BACK,
                FunctionId.MENU
            ),
            reachable
        )
    }

    @Test
    fun `mirror twins the rendered frame without text drift`() {
        val volumeSource = RecordingVolumeSource()
        volumeSource.state.value = VolumeLevel(percent = 35, isMuted = false)
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        val page = provider.mirror()
        assertEquals("VolumePage", page.pageName)
        assertEquals("Volume", page.title)
        assertEquals(listOf("35%"), page.lines)
        assertEquals(ColorId.GREY, page.accent)
    }

    @Test
    fun `mirror follows the muted state of the page it twins`() {
        val volumeSource = RecordingVolumeSource()
        volumeSource.state.value = VolumeLevel(percent = 80, isMuted = true)
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        assertEquals(listOf("Mute"), provider.mirror().lines)
    }

    @Test
    fun `retains state on resume`() {
        val volumeSource = RecordingVolumeSource()
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        assertEquals(ResumeBehavior.RETAIN, provider.onResumeBehavior)
        assertEquals("VolumePage", provider.name)
    }

    // -------------------------------------------------------------------------------------------
    // Cues (EDGE and nothing else, there is no cursor to MOVE)
    // -------------------------------------------------------------------------------------------

    private fun TestScope.cuesOf(page: CuingPage): MutableList<Cue> {
        val heard = mutableListOf<Cue>()
        backgroundScope.launch { page.cues.collect { heard += it } }
        runCurrent()
        return heard
    }

    @Test
    fun `should cue the top and the bottom of the volume`() = runTest {
        val source = RecordingVolumeSource()
        source.state.value = VolumeLevel(percent = 100, isMuted = false)
        val page = VolumePageProvider(source.level, englishClusterText, volumeControlOn, source)
        val heard = cuesOf(page)

        page.onInput(FunctionId.VOLUME_UP)
        runCurrent()

        source.state.value = VolumeLevel(percent = 0, isMuted = false)
        page.onInput(FunctionId.VOLUME_DOWN)
        runCurrent()

        assertEquals(listOf(Cue.EDGE, Cue.EDGE), heard)
    }

    @Test
    fun `should stay quiet on every other step of the volume`() = runTest {
        // The volume is not a list and has no cursor, so there is no MOVE to emit: a tone per
        // volume step would be noise.
        val source = RecordingVolumeSource()
        source.state.value = VolumeLevel(percent = 50, isMuted = false)
        val page = VolumePageProvider(source.level, englishClusterText, volumeControlOn, source)
        val heard = cuesOf(page)

        page.onInput(FunctionId.VOLUME_UP)
        runCurrent()

        assertEquals(emptyList<Cue>(), heard)
        assertEquals(1, source.raiseCalls)
    }

    @Test
    fun `should stay quiet on mute`() = runTest {
        val source = RecordingVolumeSource()
        val page = VolumePageProvider(source.level, englishClusterText, volumeControlOn, source)
        val heard = cuesOf(page)

        page.onInput(FunctionId.VOLUME_MUTE)
        runCurrent()

        assertEquals(emptyList<Cue>(), heard)
    }

    // -------------------------------------------------------------------------------------------
    // Copy
    // -------------------------------------------------------------------------------------------

    /**
     * The page names the line; somebody else says it in a language. What this checks is not the
     * translation — that lives in `values-es/` and is checked over the XML — but that the provider
     * does not invent the string, which is the half a Spanish phone would show in English.
     *
     * The percentage is the counter-example on the same frame: `"35%"` is a number and a sign, so
     * it stays inside the preset and never becomes a token.
     */
    @Test
    fun `should take the header from the resolver`() {
        val volumeSource = RecordingVolumeSource()
        volumeSource.state.value = VolumeLevel(percent = 35, isMuted = false)
        val provider = VolumePageProvider(
            volumeLevel = volumeSource.level,
            copy = { "[${it.name}]" },
            volumeControl = volumeControlOn,
            volumeSource = volumeSource
        )

        val frame = provider.render(requestId = 1) as ProjectionFrame.LiveScreen

        assertEquals("[HEADER_VOLUME]", frame.headerText)
        assertEquals("35%", frame.contentTextLine1)
    }

    @Test
    fun `should take the muted line from the resolver`() {
        val volumeSource = RecordingVolumeSource()
        volumeSource.state.value = VolumeLevel(percent = 80, isMuted = true)
        val provider = VolumePageProvider(
            volumeLevel = volumeSource.level,
            copy = { "[${it.name}]" },
            volumeControl = volumeControlOn,
            volumeSource = volumeSource
        )

        val frame = provider.render(requestId = 2) as ProjectionFrame.LiveScreen

        assertEquals("[VOLUME_MUTE]", frame.contentTextLine1)
    }

    @Test
    fun `speech emits VOLUME_LEVEL with percent on mount when unmuted and does not emit on subsequent volume changes`() = runTest {
        val volumeSource = RecordingVolumeSource()
        volumeSource.state.value = VolumeLevel(percent = 40, isMuted = false)
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        provider.speech.test {
            assertEquals(
                listOf(SpeechRequest(SpeechToken.VOLUME_LEVEL, SpeechArg.Quantity(SpeechQuantity.PERCENT, 40))),
                awaitItem()
            )
            volumeSource.state.value = VolumeLevel(percent = 50, isMuted = false)
            awaitComplete()
        }
    }

    @Test
    fun `speech emits VOLUME_MUTED on mount when muted`() = runTest {
        val volumeSource = RecordingVolumeSource()
        volumeSource.state.value = VolumeLevel(percent = 40, isMuted = true)
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, volumeControlOn, volumeSource)

        provider.speech.test {
            assertEquals(
                listOf(SpeechRequest(SpeechToken.VOLUME_MUTED)),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------------------------
    // Handlebar Volume Control
    // -------------------------------------------------------------------------------------------

    @Test
    fun `vertical keys leave the phone volume alone while handlebar volume control is off`() = runTest {
        val volumeSource = RecordingVolumeSource()
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, MutableStateFlow(false), volumeSource)

        listOf(FunctionId.PREVIOUS, FunctionId.VOLUME_UP, FunctionId.NEXT, FunctionId.VOLUME_DOWN)
            .forEach { provider.onInput(it) }

        assertEquals(0, volumeSource.raiseCalls + volumeSource.lowerCalls)
    }

    @Test
    fun `no edge cue while handlebar volume control is off`() = runTest {
        val source = RecordingVolumeSource()
        source.state.value = VolumeLevel(percent = 100, isMuted = false)
        val page = VolumePageProvider(source.level, englishClusterText, MutableStateFlow(false), source)
        val heard = cuesOf(page)

        page.onInput(FunctionId.VOLUME_UP)
        source.state.value = VolumeLevel(percent = 0, isMuted = false)
        page.onInput(FunctionId.VOLUME_DOWN)
        runCurrent()

        assertEquals(emptyList<Cue>(), heard)
    }

    @Test
    fun `turning handlebar volume control back on takes effect on the next press`() = runTest {
        val volumeSource = RecordingVolumeSource()
        val control = MutableStateFlow(false)
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, control, volumeSource)

        provider.onInput(FunctionId.VOLUME_UP)
        control.value = true
        provider.onInput(FunctionId.VOLUME_UP)

        assertEquals(1, volumeSource.raiseCalls)
    }

    @Test
    fun `mute still toggles while handlebar volume control is off`() = runTest {
        val volumeSource = RecordingVolumeSource()
        val provider = VolumePageProvider(volumeSource.level, englishClusterText, MutableStateFlow(false), volumeSource)

        provider.onInput(FunctionId.VOLUME_MUTE)

        assertEquals(1, volumeSource.muteCalls)
    }
}
