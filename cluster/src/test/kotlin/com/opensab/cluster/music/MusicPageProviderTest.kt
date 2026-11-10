package com.opensab.cluster.music
import app.cash.turbine.test
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.RecordingMusicManager
import com.opensab.domain.music.TrackMetadata
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPageProviderTest {

    @Test
    fun `renders active track metadata and playback state on live screen`() {
        val musicManager = RecordingMusicManager()
        musicManager.state.value = MusicState(
            metadata = TrackMetadata(title = "Bohemian Rhapsody", artist = "Queen"),
            status = PlaybackStatus.PLAYING
        )
        val provider = MusicPageProvider(musicManager.currentState, englishClusterText, volumeControlOn, musicManager)

        val frame = provider.render(requestId = 42)
        assertTrue(frame is ProjectionFrame.LiveScreen)
        val live = frame as ProjectionFrame.LiveScreen
        assertEquals(42, live.requestId)
        assertEquals("Bohemian Rhapsody", live.contentTextLine1)
        assertEquals("Queen", live.contentTextLine2)
    }

    @Test
    fun `back button toggles play pause without popping the page`() {
        val musicManager = RecordingMusicManager()
        val provider = MusicPageProvider(musicManager.currentState, englishClusterText, volumeControlOn, musicManager)

        val action = provider.onInput(FunctionId.BACK)
        assertEquals(NavigationAction.None, action)
        assertEquals(1, musicManager.playPauseCalls)
    }

    @Test
    fun `select button skips to next track without popping the page`() {
        val musicManager = RecordingMusicManager()
        val provider = MusicPageProvider(musicManager.currentState, englishClusterText, volumeControlOn, musicManager)

        val action = provider.onInput(FunctionId.SELECT)
        assertEquals(NavigationAction.None, action)
        assertEquals(1, musicManager.nextCalls)
    }

    @Test
    fun `previous and next buttons adjust volume without changing navigation stack`() {
        val musicManager = RecordingMusicManager()
        val provider = MusicPageProvider(musicManager.currentState, englishClusterText, volumeControlOn, musicManager)

        val prevAction = provider.onInput(FunctionId.PREVIOUS)
        assertEquals(NavigationAction.None, prevAction)
        assertEquals(1, musicManager.volUpCalls)

        val nextAction = provider.onInput(FunctionId.NEXT)
        assertEquals(NavigationAction.None, nextAction)
        assertEquals(1, musicManager.volDownCalls)
    }

    @Test
    fun `menu button resets to the root menu`() {
        val musicManager = RecordingMusicManager()
        val provider = MusicPageProvider(musicManager.currentState, englishClusterText, volumeControlOn, musicManager)

        val action = provider.onInput(FunctionId.MENU)
        assertEquals(NavigationAction.Reset, action)
    }

    @Test
    fun `dedicated volume buttons adjust volume`() {
        val musicManager = RecordingMusicManager()
        val provider = MusicPageProvider(musicManager.currentState, englishClusterText, volumeControlOn, musicManager)

        provider.onInput(FunctionId.VOLUME_UP)
        assertEquals(1, musicManager.volUpCalls)

        provider.onInput(FunctionId.VOLUME_DOWN)
        assertEquals(1, musicManager.volDownCalls)

        provider.onInput(FunctionId.VOLUME_MUTE)
        assertEquals(1, musicManager.muteCalls)
    }

    @Test
    fun `retains state on resume`() {
        val musicManager = RecordingMusicManager()
        val provider = MusicPageProvider(musicManager.currentState, englishClusterText, volumeControlOn, musicManager)

        assertEquals(ResumeBehavior.RETAIN, provider.onResumeBehavior)
    }

    @Test
    fun `mirror declares one zone per reachable button`() {
        val mirror = mirrorWith(PlaybackStatus.PLAYING)

        assertEquals(
            listOf(
                MirrorSlot.PRIMARY,
                MirrorSlot.RIGHT,
                MirrorSlot.UP,
                MirrorSlot.DOWN,
                MirrorSlot.LEFT,
                MirrorSlot.HEADER
            ).sortedBy { it.name },
            mirror.actions.map { it.slot }.sortedBy { it.name }
        )
        assertTrue(mirror.actions.all { it.enabled })
    }

    @Test
    fun `mirror labels the primary zone pause while playing`() {
        val primary = mirrorWith(PlaybackStatus.PLAYING).zone(MirrorSlot.PRIMARY)

        assertEquals(MirrorActionToken.PAUSE, primary.token)
        assertEquals(FunctionId.BACK, primary.functionId)
        assertEquals(emptySet<FunctionId>(), primary.aliases)
    }

    @Test
    fun `mirror labels the primary zone play while not playing`() {
        assertEquals(
            MirrorActionToken.PLAY,
            mirrorWith(PlaybackStatus.PAUSED).zone(MirrorSlot.PRIMARY).token
        )
        assertEquals(
            MirrorActionToken.PLAY,
            mirrorWith(PlaybackStatus.STOPPED).zone(MirrorSlot.PRIMARY).token
        )
        assertEquals(
            MirrorActionToken.PLAY,
            mirrorWith(PlaybackStatus.NONE).zone(MirrorSlot.PRIMARY).token
        )
    }

    @Test
    fun `mirror routes the select button to the next track zone`() {
        val right = mirrorWith(PlaybackStatus.PLAYING).zone(MirrorSlot.RIGHT)

        assertEquals(MirrorActionToken.NEXT_TRACK, right.token)
        assertEquals(FunctionId.SELECT, right.functionId)
        assertEquals(emptySet<FunctionId>(), right.aliases)
    }

    @Test
    fun `mirror folds the dedicated volume buttons into the carousel zones as aliases`() {
        val mirror = mirrorWith(PlaybackStatus.PLAYING)

        val up = mirror.zone(MirrorSlot.UP)
        assertEquals(MirrorActionToken.VOLUME_UP, up.token)
        assertEquals(FunctionId.PREVIOUS, up.functionId)
        assertEquals(setOf(FunctionId.VOLUME_UP), up.aliases)

        val down = mirror.zone(MirrorSlot.DOWN)
        assertEquals(MirrorActionToken.VOLUME_DOWN, down.token)
        assertEquals(FunctionId.NEXT, down.functionId)
        assertEquals(setOf(FunctionId.VOLUME_DOWN), down.aliases)
    }

    @Test
    fun `mirror puts mute on the left and the carousel escape in the header`() {
        val mirror = mirrorWith(PlaybackStatus.PLAYING)

        val left = mirror.zone(MirrorSlot.LEFT)
        assertEquals(MirrorActionToken.VOLUME_MUTE, left.token)
        assertEquals(FunctionId.VOLUME_MUTE, left.functionId)
        assertEquals(emptySet<FunctionId>(), left.aliases)

        val header = mirror.zone(MirrorSlot.HEADER)
        assertEquals(MirrorActionToken.OPEN_ROOT_MENU, header.token)
        assertEquals(FunctionId.MENU, header.functionId)
        assertEquals(emptySet<FunctionId>(), header.aliases)
    }

    /**
     * The page names the line; somebody else says it in a language. What this checks is not the
     * translation — that lives in `values-es/` and is checked over the XML — but that the provider
     * does not invent the string, which is the half a Spanish phone would show in English.
     *
     * Both lines come off the one token because the substitute for a missing title *is* the name of
     * the page: `LiveScreenPresets.music` takes one `header` and uses it for both.
     */
    @Test
    fun `should take the header and the missing-title line from the resolver`() {
        val musicManager = RecordingMusicManager()
        val provider = MusicPageProvider(
            musicState = musicManager.currentState,
            copy = { "[${it.name}]" },
            volumeControl = volumeControlOn,
            musicManager = musicManager
        )

        val frame = provider.render(requestId = 1) as ProjectionFrame.LiveScreen

        assertEquals("[HEADER_MUSIC]", frame.headerText)
        assertEquals("[HEADER_MUSIC]", frame.contentTextLine1)
    }

    private fun mirrorWith(status: PlaybackStatus): MirrorPage {
        val musicManager = RecordingMusicManager()
        musicManager.state.value = MusicState(
            metadata = TrackMetadata(title = "Bohemian Rhapsody", artist = "Queen"),
            status = status
        )
        return MusicPageProvider(musicManager.currentState, englishClusterText, volumeControlOn, musicManager).mirror()
    }

    private fun MirrorPage.zone(slot: MirrorSlot): MirrorAction =
        actions.single { it.slot == slot }

    @Test
    fun `speech emits MUSIC_LIVE once on mount and does not emit on subsequent state changes`() = runTest {
        val musicManager = RecordingMusicManager()
        val provider = MusicPageProvider(musicManager.currentState, englishClusterText, volumeControlOn, musicManager)

        provider.speech.test {
            assertEquals(listOf(SpeechRequest(SpeechToken.MUSIC_LIVE)), awaitItem())
            musicManager.state.value = MusicState(
                metadata = TrackMetadata(title = "New Song", artist = "New Artist"),
                status = PlaybackStatus.PLAYING
            )
            awaitComplete()
        }
    }
}
