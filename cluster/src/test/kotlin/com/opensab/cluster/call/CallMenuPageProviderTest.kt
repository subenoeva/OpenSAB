package com.opensab.cluster.call

import app.cash.turbine.test
import com.opensab.cluster.CuingPage
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
import com.opensab.cluster.StubPageProvider
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.audio.Cue
import com.opensab.domain.contacts.CallMenuAccess
import com.opensab.domain.contacts.CallMenuGates
import com.opensab.domain.contacts.CallTarget
import com.opensab.domain.contacts.MockCallMenuAccess
import com.opensab.domain.contacts.PhoneKind
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.speech.SpeechWord
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallMenuPageProviderTest {

    private val frame = ProjectionFrame.RequestSabMode(1)

    private fun menuPage(
        access: CallMenuAccess = MockCallMenuAccess(),
        copy: ClusterTextResolver = englishClusterText,
        dialPageFactory: (DialSeed) -> PageProvider = { StubPageProvider("dial", frame) }
    ) = CallMenuPageProvider(access, dialPageFactory, copy)

    @Test
    fun `should paint two rows and nothing else`() {
        assertEquals(listOf(CallMenuItem.VOICE, CallMenuItem.FAVORITES), CallMenuItem.entries)
        val page = menuPage()

        val menu1 = page.render(1) as ProjectionFrame.MenuScreen
        assertEquals("Phone", menu1.headerText)
        assertEquals(ColorId.PURPLE, menu1.headerBgColorId)
        assertEquals(ColorId.PURPLE_LIGHT, menu1.bodyBgColorId)
        assertEquals(ColorId.PURE_WHITE, menu1.headerTextColorId)
        assertEquals("By voice", menu1.contentTextLine1)
        assertEquals(IconId.MIC, menu1.contentIconId)
        assertEquals(IconId.IC_NONE, menu1.upActionIconId)
        assertEquals(IconId.MENU_ARROW_DOWN, menu1.downActionIconId)
        assertEquals(IconId.MENU_ARROW_LEFT, menu1.leftActionIconId)

        page.onInput(FunctionId.NEXT)
        val menu2 = page.render(2) as ProjectionFrame.MenuScreen
        assertEquals("Favorites", menu2.contentTextLine1)
        assertEquals(IconId.FAVORITE_CONTACT, menu2.contentIconId)
        assertEquals(IconId.MENU_ARROW_UP, menu2.upActionIconId)
        assertEquals(IconId.IC_NONE, menu2.downActionIconId)
        assertEquals(IconId.MENU_ARROW_LEFT, menu2.leftActionIconId)
    }

    @Test
    fun `should move the cursor with previous and next`() {
        val page = menuPage()
        assertEquals(0, page.selectionIndex)

        val actionPrev0 = page.onInput(FunctionId.PREVIOUS)
        assertEquals(0, page.selectionIndex)
        assertEquals(NavigationAction.Invalidate, actionPrev0)

        val actionNext = page.onInput(FunctionId.NEXT)
        assertEquals(1, page.selectionIndex)
        assertEquals(NavigationAction.Invalidate, actionNext)

        val actionNext1 = page.onInput(FunctionId.NEXT)
        assertEquals(1, page.selectionIndex)
        assertEquals(NavigationAction.Invalidate, actionNext1)

        val actionPrev1 = page.onInput(FunctionId.PREVIOUS)
        assertEquals(0, page.selectionIndex)
        assertEquals(NavigationAction.Invalidate, actionPrev1)
    }

    @Test
    fun `should open the dialler seeded with voice on the first row`() = runTest {
        val access = MockCallMenuAccess(CallMenuGates(voice = true, favorites = emptyList()))
        var seed: DialSeed? = null
        val page = menuPage(access) { asked -> seed = asked; StubPageProvider("dial", frame) }
        backgroundScope.launch { page.work() }
        runCurrent()

        val action = page.onInput(FunctionId.SELECT)

        assertTrue(action is NavigationAction.Next)
        assertEquals(DialSeed.Voice, seed)
    }

    @Test
    fun `should open the dialler seeded with the favourites on the second row`() = runTest {
        val ana = CallTarget("Ana Ruiz", "600100100", PhoneKind.MOBILE)
        val access = MockCallMenuAccess(CallMenuGates(voice = true, favorites = listOf(ana)))
        var seed: DialSeed? = null
        val page = menuPage(access) { asked -> seed = asked; StubPageProvider("dial", frame) }
        backgroundScope.launch { page.work() }
        runCurrent()

        page.onInput(FunctionId.NEXT)
        val action = page.onInput(FunctionId.SELECT)

        assertTrue(action is NavigationAction.Next)
        assertEquals(DialSeed.Favorites(listOf(ana)), seed)
    }

    @Test
    fun `should leave on back`() {
        val page = menuPage()

        val action = page.onInput(FunctionId.BACK)

        assertEquals(NavigationAction.Pop, action)
    }

    @Test
    fun `should reset to the carousel on MENU`() {
        val page = menuPage()

        val action = page.onInput(FunctionId.MENU)

        assertEquals(NavigationAction.Reset, action)
    }

    @Test
    fun `should keep both rows shut until it has read the gates`() {
        val page = menuPage()

        val menu = page.render(1) as ProjectionFrame.MenuScreen

        // Shut is the only value the first frame can paint without lying: at that instant SELECT
        // really does return None, so the arrow tells the truth.
        assertEquals(IconId.IC_NONE, menu.rightActionIconId)
        assertEquals(NavigationAction.None, page.onInput(FunctionId.SELECT))

        page.onInput(FunctionId.NEXT)
        val menu2 = page.render(2) as ProjectionFrame.MenuScreen
        assertEquals(IconId.IC_NONE, menu2.rightActionIconId)
        assertEquals(NavigationAction.None, page.onInput(FunctionId.SELECT))
    }

    @Test
    fun `should read the gates once it is mounted`() = runTest {
        val access = MockCallMenuAccess(CallMenuGates(voice = true, favorites = emptyList()))
        val page = menuPage(access)

        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(1, access.gatesCalls)
        assertEquals(IconId.MENU_ARROW_RIGHT, (page.render(1) as ProjectionFrame.MenuScreen).rightActionIconId)
    }

    @Test
    fun `should not read the gates again on a redraw`() = runTest {
        val access = MockCallMenuAccess(CallMenuGates(voice = true, favorites = emptyList()))
        val page = menuPage(access)
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(1, access.gatesCalls)

        page.onInput(FunctionId.NEXT)
        page.render(2)
        page.onInput(FunctionId.PREVIOUS)
        page.render(3)
        runCurrent()

        assertEquals("moving cursor or rendering does not read gates again", 1, access.gatesCalls)
    }

    @Test
    fun `should close the row the gates close`() = runTest {
        val ana = CallTarget("Ana", "600100100", PhoneKind.MOBILE)

        // 1. voice open, favorites closed
        val access1 = MockCallMenuAccess(CallMenuGates(voice = true, favorites = emptyList()))
        val page1 = menuPage(access1)
        val job1 = backgroundScope.launch { page1.work() }
        runCurrent()
        assertEquals(IconId.MENU_ARROW_RIGHT, (page1.render(1) as ProjectionFrame.MenuScreen).rightActionIconId)
        page1.onInput(FunctionId.NEXT)
        assertEquals(IconId.IC_NONE, (page1.render(2) as ProjectionFrame.MenuScreen).rightActionIconId)
        job1.cancel()

        // 2. voice closed, favorites open
        val access2 = MockCallMenuAccess(CallMenuGates(voice = false, favorites = listOf(ana)))
        val page2 = menuPage(access2)
        val job2 = backgroundScope.launch { page2.work() }
        runCurrent()
        assertEquals(IconId.IC_NONE, (page2.render(1) as ProjectionFrame.MenuScreen).rightActionIconId)
        page2.onInput(FunctionId.NEXT)
        assertEquals(IconId.MENU_ARROW_RIGHT, (page2.render(2) as ProjectionFrame.MenuScreen).rightActionIconId)
        job2.cancel()

        // 3. both closed
        val access3 = MockCallMenuAccess(CallMenuGates(voice = false, favorites = emptyList()))
        val page3 = menuPage(access3)
        val job3 = backgroundScope.launch { page3.work() }
        runCurrent()
        assertEquals(IconId.IC_NONE, (page3.render(1) as ProjectionFrame.MenuScreen).rightActionIconId)
        page3.onInput(FunctionId.NEXT)
        assertEquals(IconId.IC_NONE, (page3.render(2) as ProjectionFrame.MenuScreen).rightActionIconId)
        job3.cancel()
    }

    @Test
    fun `should refuse to open a shut row`() = runTest {
        val access = MockCallMenuAccess(CallMenuGates(voice = true, favorites = emptyList()))
        var built = 0
        val page = menuPage(access) { built++; StubPageProvider("dial", frame) }
        backgroundScope.launch { page.work() }
        runCurrent()

        page.onInput(FunctionId.NEXT)
        val action = page.onInput(FunctionId.SELECT)

        assertEquals(NavigationAction.None, action)
        assertEquals(0, built)
    }

    @Test
    fun `should close the arrow, the press and the mirror zone together`() = runTest {
        val ana = CallTarget("Ana", "600100100", PhoneKind.MOBILE)
        val access = MockCallMenuAccess(CallMenuGates(voice = false, favorites = listOf(ana)))
        val page = menuPage(access)
        backgroundScope.launch { page.work() }
        runCurrent()

        // Row 0 (VOICE) is shut
        val frame0 = page.render(1) as ProjectionFrame.MenuScreen
        assertEquals(IconId.IC_NONE, frame0.rightActionIconId)
        val mirror0 = page.mirror()
        val openAction0 = mirror0.actions.first { it.token == MirrorActionToken.OPEN }
        assertEquals(false, openAction0.enabled)
        assertEquals(NavigationAction.None, page.onInput(FunctionId.SELECT))

        // Row 1 (FAVORITES) is open
        page.onInput(FunctionId.NEXT)
        val frame1 = page.render(2) as ProjectionFrame.MenuScreen
        assertEquals(IconId.MENU_ARROW_RIGHT, frame1.rightActionIconId)
        val mirror1 = page.mirror()
        val openAction1 = mirror1.actions.first { it.token == MirrorActionToken.OPEN }
        assertEquals(true, openAction1.enabled)
        assertTrue(page.onInput(FunctionId.SELECT) is NavigationAction.Next)
    }

    @Test
    fun `should redraw when the gates arrive`() = runTest {
        val access = MockCallMenuAccess(CallMenuGates(voice = true, favorites = emptyList()))
        val page = menuPage(access)

        val collected = mutableListOf<Unit>()
        val job = backgroundScope.launch { page.invalidations.toList(collected) }
        runCurrent()

        assertEquals("mounting emits nothing", 0, collected.size)

        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals("gates arriving emits exactly one redraw", 1, collected.size)
        job.cancel()
    }

    @Test
    fun `should read the gates again the next time it is mounted`() = runTest {
        val access = MockCallMenuAccess(CallMenuGates(voice = true, favorites = emptyList()))
        val page = menuPage(access)

        val job1 = backgroundScope.launch { page.work() }
        runCurrent()
        assertEquals(1, access.gatesCalls)
        job1.cancel()

        val job2 = backgroundScope.launch { page.work() }
        runCurrent()
        assertEquals(2, access.gatesCalls)
        job2.cancel()
    }

    // -------------------------------------------------------------------------------------------
    // Cues
    // -------------------------------------------------------------------------------------------

    private fun TestScope.cuesOf(page: CuingPage): MutableList<Cue> {
        val heard = mutableListOf<Cue>()
        backgroundScope.launch { page.cues.collect { heard += it } }
        runCurrent()
        return heard
    }

    @Test
    fun `should cue the move between its two rows`() = runTest {
        val page = menuPage()
        val heard = cuesOf(page)

        page.onInput(FunctionId.NEXT)
        runCurrent()

        assertEquals(listOf(Cue.MOVE), heard)
    }

    @Test
    fun `should cue both ends of its two rows`() = runTest {
        val page = menuPage()
        val heard = cuesOf(page)

        page.onInput(FunctionId.PREVIOUS) // already at the top
        page.onInput(FunctionId.NEXT)     // moves to the second row
        page.onInput(FunctionId.NEXT)     // already at the bottom
        runCurrent()

        assertEquals(listOf(Cue.EDGE, Cue.MOVE, Cue.EDGE), heard)
    }

    @Test
    fun `should take its header from the resolver`() {
        val page = menuPage(copy = ClusterTextResolver { "[${it.name}]" })

        val menu = page.render(1) as ProjectionFrame.MenuScreen
        assertEquals("[HEADER_PHONE]", menu.headerText)
    }

    @Test
    fun `should take the label of both rows from the resolver`() {
        val page = menuPage(copy = ClusterTextResolver { "[${it.name}]" })

        val row1 = (page.render(1) as ProjectionFrame.MenuScreen).contentTextLine1
        page.onInput(FunctionId.NEXT)
        val row2 = (page.render(2) as ProjectionFrame.MenuScreen).contentTextLine1

        assertEquals("[CALL_MENU_BY_VOICE]", row1)
        assertEquals("[CALL_MENU_FAVORITES]", row2)
    }

    @Test
    fun `speech emits nothing before gates are read`() = runTest {
        val page = menuPage()
        page.speech.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `speech emits MENU_TITLE and row on mount`() = runTest {
        val access = MockCallMenuAccess(CallMenuGates(voice = true, favorites = listOf(CallTarget("Carlos", "123", PhoneKind.MOBILE))))
        val page = menuPage(access)
        backgroundScope.launch { page.work() }
        page.speech.test {
            val script = awaitItem()
            assertEquals(2, script.size)
            assertEquals(SpeechToken.MENU_TITLE, script[0].token)
            assertEquals(listOf(SpeechArg.Word(SpeechWord.MENU_PHONE)), script[0].args)
            assertEquals(SpeechToken.CALL_MENU_VOICE, script[1].token)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `speech emits only row on cursor movement`() = runTest {
        val access = MockCallMenuAccess(CallMenuGates(voice = true, favorites = listOf(CallTarget("Carlos", "123", PhoneKind.MOBILE))))
        val page = menuPage(access)
        backgroundScope.launch { page.work() }
        page.speech.test {
            awaitItem() // mount

            page.onInput(FunctionId.NEXT)
            val script = awaitItem()
            assertEquals(1, script.size)
            assertEquals(SpeechToken.CALL_MENU_FAVORITES, script[0].token)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `closed voice or empty favorites emits disabled token without help`() = runTest {
        val access = MockCallMenuAccess(CallMenuGates(voice = false, favorites = emptyList()))
        val page = menuPage(access)
        backgroundScope.launch { page.work() }
        page.speech.test {
            val script1 = awaitItem()
            assertEquals(2, script1.size)
            assertEquals(SpeechToken.MENU_TITLE, script1[0].token)
            assertEquals(SpeechToken.CALL_MENU_VOICE_DISABLED, script1[1].token)

            page.onInput(FunctionId.NEXT)
            val script2 = awaitItem()
            assertEquals(1, script2.size)
            assertEquals(SpeechToken.CALL_MENU_FAVORITES_EMPTY, script2[0].token)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
