package com.opensab.cluster.nav

import app.cash.turbine.test
import com.opensab.cluster.CuingPage
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.StubPageProvider
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.audio.Cue
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.speech.SpeechWord
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavMenuPageProviderTest {

    private val voiceSearchPage = StubPageProvider("VoiceSearch", ProjectionFrame.RequestSabMode(10))
    private val copy = englishClusterText

    private fun provider(): NavMenuPageProvider =
        NavMenuPageProvider(voicePageFactory = { voiceSearchPage }, copy = copy)

    @Test
    fun `V-NAV-01 should render inactive navigation menu with green theme and mic icon`() {
        val page = provider()
        val frame = page.render(requestId = 42) as ProjectionFrame.MenuScreen

        assertEquals(42, frame.requestId)
        assertEquals("Navigation", frame.headerText)
        assertEquals(ColorId.GREEN, frame.headerBgColorId)
        assertEquals(ColorId.PURE_WHITE, frame.headerTextColorId)
        assertEquals(ColorId.GREEN_LIGHT, frame.bodyBgColorId)
        assertEquals(IconId.MIC, frame.contentIconId)
        assertEquals(ColorId.PURE_WHITE, frame.contentIconTintColorId)
        assertEquals("Voice Search", frame.contentTextLine1)
        assertEquals(ColorId.PURE_WHITE, frame.contentTextColorIdLine1)
        assertEquals(IconId.MENU_ARROW_LEFT, frame.leftActionIconId)
        assertEquals(IconId.MENU_ARROW_RIGHT, frame.rightActionIconId)
    }

    @Test
    fun `V-NAV-02 navigation controls and edge cues on single item menu`() = runTest {
        val page = provider()

        page.cues.test {
            // In a single-item menu, NEXT and PREVIOUS hit the edge
            assertEquals(NavigationAction.Invalidate, page.onInput(FunctionId.NEXT))
            assertEquals(Cue.EDGE, awaitItem())

            assertEquals(NavigationAction.Invalidate, page.onInput(FunctionId.PREVIOUS))
            assertEquals(Cue.EDGE, awaitItem())

            // Volume controls act as Next / Previous cursor navigation
            assertEquals(NavigationAction.Invalidate, page.onInput(FunctionId.VOLUME_UP))
            assertEquals(Cue.EDGE, awaitItem())

            assertEquals(NavigationAction.Invalidate, page.onInput(FunctionId.VOLUME_DOWN))
            assertEquals(Cue.EDGE, awaitItem())

            // Unhandled inputs return None and emit no cues
            assertEquals(NavigationAction.None, page.onInput(FunctionId.VOLUME_MUTE))

            // SELECT opens voice search page
            val selectAction = page.onInput(FunctionId.SELECT)
            assertTrue(selectAction is NavigationAction.Next)
            assertEquals(voiceSearchPage, (selectAction as NavigationAction.Next).page)

            // BACK pops to root carousel
            assertEquals(NavigationAction.Pop, page.onInput(FunctionId.BACK))

            // MENU resets
            assertEquals(NavigationAction.Reset, page.onInput(FunctionId.MENU))
        }
    }

    @Test
    fun `should declare correct mirror actions with open enabled`() {
        val page = provider()
        val mirror = page.mirror()

        assertEquals("NavMenu", mirror.pageName)
        val tokens = mirror.actions.map { it.token }
        assertTrue(tokens.contains(MirrorActionToken.CAROUSEL_PREVIOUS))
        assertTrue(tokens.contains(MirrorActionToken.CAROUSEL_NEXT))
        assertTrue(tokens.contains(MirrorActionToken.OPEN))
        assertTrue(tokens.contains(MirrorActionToken.GO_BACK))
        assertTrue(tokens.contains(MirrorActionToken.OPEN_ROOT_MENU))

        val openAction = mirror.actions.first { it.token == MirrorActionToken.OPEN }
        assertTrue(openAction.enabled)
    }

    @Test
    fun `should declare RETAIN resume behavior`() {
        assertEquals(ResumeBehavior.RETAIN, provider().onResumeBehavior)
    }

    @Test
    fun `should implement CuingPage and SpeakingPage`() {
        val page: PageProvider = provider()
        assertTrue("Must implement CuingPage", page is CuingPage)
        assertTrue("Must implement SpeakingPage", page is SpeakingPage)
    }

    @Test
    fun `speech emits MENU_TITLE and NAV_MENU_VOICE on mount`() = runTest {
        val page = provider()
        page.speech.test {
            val script = awaitItem()
            assertEquals(2, script.size)
            assertEquals(SpeechToken.MENU_TITLE, script[0].token)
            assertEquals(listOf(SpeechArg.Word(SpeechWord.MENU_NAVIGATION)), script[0].args)
            assertEquals(SpeechToken.NAV_MENU_VOICE, script[1].token)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
