package com.opensab.cluster

import com.opensab.cluster.mirror.MIRROR_REQUEST_ID
import com.opensab.cluster.mirror.MirrorDelivery
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorState
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.cluster.mirror.mirrorPageOf
import com.opensab.cluster.rootmenu.RootMenuItem
import com.opensab.cluster.rootmenu.RootMenuPageProvider
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.audio.AudioFocus
import com.opensab.domain.audio.AudioFocusKind
import com.opensab.domain.audio.AudioFocusLease
import com.opensab.domain.speech.MockSpeechSynthesizer
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.input.InputSource
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.input.FakeInputDispatcher
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.ProjectionRejectedException
import com.opensab.sab.session.projection.RecordingScreenProjector
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine loop wiring.
 *
 * On entering SAB mode the engine mounts RootMenuPageProvider on the default stack and projects the
 * root MENU frame. SELECT on the music entry pushes a page and projects a LIVE_SCREEN. While the
 * cluster is in HU mode the engine suspends frame emission and regenerates the current frame as
 * soon as SAB mode comes back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BaseStateManagerTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun manager(
        projector: RecordingScreenProjector,
        musicPage: PageProvider? = null,
        projectionMirror: ProjectionMirror = ProjectionMirror(),
        activeIndicators: ActiveIndicatorsPublisher = ActiveIndicatorsPublisher(projector),
        interruptSource: InterruptSource = NoInterruptions,
        pageVoice: PageVoice? = null
    ) = BaseStateManager(
        screenProjector = projector,
        requestIdGenerator = RequestIdGenerator(),
        capabilities = VehicleCapabilitiesHolder(),
        activeIndicators = activeIndicators,
        projectionMirror = projectionMirror,
        interruptSource = interruptSource,
        pageVoice = pageVoice,
        rootPageFactory = {
            RootMenuPageProvider(
                entries = allRootMenuEntries(),
                pageFactory = { item ->
                    val stub = StubPageProvider("stub", ProjectionFrame.RequestSabMode(1))
                    if (item == RootMenuItem.MUSIC) musicPage ?: stub else stub
                },
                copy = englishClusterText
            )
        }
    )

    @Test
    fun projectsTheRootMenuFrameWhenSabModeIsEntered() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val stateManager = manager(projector)
        stateManager.start(input, backgroundScope)

        input.enterMode(DashboardMode.SAB_MODE)

        assertEquals("Root menu must be mounted on the default stack", 1, stateManager.stack.size)
        assertEquals(1, projector.frames.size)
        val frame = projector.frames.single()
        assertTrue("Cold start must project a MENU frame", frame is ProjectionFrame.MenuScreen)
        assertEquals(
            englishClusterText.resolve(RootMenuItem.entries.first().title),
            (frame as ProjectionFrame.MenuScreen).headerText
        )
    }

    @Test
    fun reprojectsTheMenuWhenTheCursorMoves() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        manager(projector).start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)

        input.press(FunctionId.NEXT)

        assertEquals(2, projector.frames.size)
        assertEquals(
            englishClusterText.resolve(RootMenuItem.entries[1].title),
            (projector.frames.last() as ProjectionFrame.MenuScreen).headerText
        )
    }

    @Test
    fun pushesTheMusicPageWhenSelectIsPressedOnTheMusicEntry() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val musicPage = StubPageProvider("music", liveScreen())
        val stateManager = manager(projector, musicPage)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MUSIC)) { input.press(FunctionId.NEXT) }

        input.press(FunctionId.SELECT)

        assertEquals("Music page must be stacked over the root menu", 2, stateManager.stack.size)
        assertTrue(projector.frames.last() is ProjectionFrame.LiveScreen)
    }

    @Test
    fun popsBackToTheRootPageWhenBackIsPressed() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val musicPage = StubPageProvider(
            name = "music",
            frame = liveScreen(),
            inputResult = { functionId ->
                if (functionId == FunctionId.BACK) NavigationAction.Pop else NavigationAction.None
            }
        )
        val stateManager = manager(projector, musicPage)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MUSIC)) { input.press(FunctionId.NEXT) }
        input.press(FunctionId.SELECT)

        input.press(FunctionId.BACK)

        assertEquals(1, stateManager.stack.size)
        assertTrue(projector.frames.last() is ProjectionFrame.MenuScreen)
    }

    @Test
    fun suspendsFrameEmissionWhileTheClusterIsInHuMode() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        manager(projector).start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        val projectedInSabMode = projector.frames.size

        input.enterMode(DashboardMode.HU_MODE)
        input.press(FunctionId.NEXT)
        input.press(FunctionId.NEXT)

        assertEquals(
            "No frame may be written while the native tachometer owns the cluster",
            projectedInSabMode,
            projector.frames.size
        )
    }

    @Test
    fun regeneratesTheCurrentFrameWhenSabModeComesBack() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        manager(projector).start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        input.press(FunctionId.NEXT)
        input.enterMode(DashboardMode.HU_MODE)
        val projectedBeforeReturn = projector.frames.size

        input.enterMode(DashboardMode.SAB_MODE)

        assertEquals(projectedBeforeReturn + 1, projector.frames.size)
        assertEquals(
            "The cursor position survives the trip through HU mode",
            englishClusterText.resolve(RootMenuItem.entries[1].title),
            (projector.frames.last() as ProjectionFrame.MenuScreen).headerText
        )
    }

    @Test
    fun assignsAFreshRequestIdToEveryFrame() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        manager(projector).start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        input.press(FunctionId.NEXT)
        input.press(FunctionId.NEXT)

        val ids = projector.frames.map { it.requestId }
        assertEquals("Every projected frame carries its own requestId", ids.size, ids.toSet().size)
        assertTrue(ids.all { it in 1..255 })
    }

    // -------------------------------------------------------------------------------------------
    // Projecting on an assumed grant: the rider opted into writing without a 0x09
    // -------------------------------------------------------------------------------------------

    @Test
    fun mountsTheRootAndProjectsWhenTheGrantIsAssumed() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val stateManager = manager(projector)
        stateManager.start(input, backgroundScope)

        input.assumeSabMode()

        assertEquals(1, stateManager.stack.size)
        assertTrue(projector.frames.single() is ProjectionFrame.MenuScreen)
    }

    @Test
    fun aHandlebarPressMovesThePageWhileTheGrantIsAssumed() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        manager(projector).start(input, backgroundScope)
        input.assumeSabMode()

        input.press(FunctionId.NEXT)

        assertEquals(
            englishClusterText.resolve(RootMenuItem.entries[1].title),
            (projector.frames.last() as ProjectionFrame.MenuScreen).headerText
        )
    }

    /**
     * The frames written on the assumption may have been thrown away by a cluster still in
     * HU_MODE, so the confirmation re-projects the page the rider is on, once, for real. It does
     * not start over: the stack is the one the rider has been moving through.
     */
    @Test
    fun theReal0x09ReprojectsTheCurrentPageWithoutMountingTheRootAgain() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        var rootsBuilt = 0
        val stateManager = BaseStateManager(
            screenProjector = projector,
            requestIdGenerator = RequestIdGenerator(),
            capabilities = VehicleCapabilitiesHolder(),
            activeIndicators = ActiveIndicatorsPublisher(projector),
            projectionMirror = ProjectionMirror(),
            rootPageFactory = {
                rootsBuilt++
                RootMenuPageProvider(
                    entries = allRootMenuEntries(),
                    pageFactory = { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) },
                    copy = englishClusterText
                )
            }
        )
        stateManager.start(input, backgroundScope)
        input.assumeSabMode()
        input.press(FunctionId.NEXT)
        val root = stateManager.stack.peek()
        val writtenBlind = projector.frames.size

        input.enterMode(DashboardMode.SAB_MODE)

        assertEquals("The root is mounted once per session", 1, rootsBuilt)
        assertTrue("Same page instance, same cursor", root === stateManager.stack.peek())
        assertEquals("Exactly one frame for the confirmation", writtenBlind + 1, projector.frames.size)
        assertEquals(
            englishClusterText.resolve(RootMenuItem.entries[1].title),
            (projector.frames.last() as ProjectionFrame.MenuScreen).headerText
        )
        assertEquals(
            "What the cluster shows is unknown until that frame: the projector must not skip it as a repeat",
            listOf(writtenBlind),
            projector.invalidatedAt
        )
    }

    @Test
    fun anOrdinaryGrantNeverInvalidatesTheShownBase() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        manager(projector).start(input, backgroundScope)

        input.enterMode(DashboardMode.SAB_MODE)
        input.enterMode(DashboardMode.HU_MODE)
        input.enterMode(DashboardMode.SAB_MODE)

        assertTrue(projector.invalidatedAt.isEmpty())
    }

    @Test
    fun aHuModeAfterTheConfirmationSuspendsEmissionAsAlways() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val mirror = ProjectionMirror()
        manager(projector, projectionMirror = mirror).start(input, backgroundScope)
        input.assumeSabMode()
        input.enterMode(DashboardMode.SAB_MODE)
        val written = projector.frames.size

        input.enterMode(DashboardMode.HU_MODE)
        input.press(FunctionId.NEXT)

        assertEquals(written, projector.frames.size)
        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = false), mirror.state.value)
    }

    /** A Receipt on an assumed grant says the BTU took the bytes, not that the rider sees them. */
    @Test
    fun theMirrorSaysTheHandoverIsUnconfirmedUntilThe0x09() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val mirror = ProjectionMirror()
        manager(projector, projectionMirror = mirror).start(input, backgroundScope)

        input.assumeSabMode()
        assertEquals(
            MirrorDelivery.HandoverUnconfirmed,
            (mirror.state.value as MirrorState.Projecting).delivery
        )

        input.enterMode(DashboardMode.SAB_MODE)
        assertEquals(MirrorDelivery.Confirmed, (mirror.state.value as MirrorState.Projecting).delivery)
    }

    @Test
    fun aRejectionOnAnAssumedGrantIsStillARejection() = testScope.runTest {
        val projector = RecordingScreenProjector()
        projector.failWith = ProjectionRejectedException(status = 0x0C, errorPosition = 7)
        val input = FakeInputDispatcher()
        val mirror = ProjectionMirror()
        manager(projector, projectionMirror = mirror).start(input, backgroundScope)

        input.assumeSabMode()

        assertEquals(
            MirrorDelivery.Rejected(status = 0x0C, errorPosition = 7),
            (mirror.state.value as MirrorState.Projecting).delivery
        )
    }

    // -------------------------------------------------------------------------------------------
    // Mirror publication
    // -------------------------------------------------------------------------------------------

    /**
     * The stack is deliberately not cleared on HU_MODE, so the same page is re-projected when SAB
     * comes back and the mirror recovers it intact.
     */
    @Test
    fun `HU_MODE hands the screen back and writes no frame`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val mirror = ProjectionMirror()
        val stateManager = manager(projector, projectionMirror = mirror)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        val projectedInSabMode = projector.frames.size

        input.enterMode(DashboardMode.HU_MODE)

        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = false), mirror.state.value)
        assertEquals(projectedInSabMode, projector.frames.size)

        input.enterMode(DashboardMode.SAB_MODE)
        val recovered = mirror.state.value as MirrorState.Projecting
        assertEquals("RootMenu", recovered.page.pageName)
    }

    @Test
    fun `projecting mounts the mirrored page with the trail of the whole stack`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val mirror = ProjectionMirror()
        val musicPage = StubPageProvider("MusicPage", liveScreen())
        manager(projector, musicPage, projectionMirror = mirror).start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MUSIC)) { input.press(FunctionId.NEXT) }

        input.press(FunctionId.SELECT)

        val state = mirror.state.value as MirrorState.Projecting
        assertEquals("MusicPage", state.page.pageName)
        assertEquals(listOf("RootMenu", "MusicPage"), state.trail)
    }

    /**
     * The page goes up before the frame is written and the verdict lands after it. That order is
     * what makes a rejection leave the mirror painted and marked stale, which is the truth: the
     * cluster keeps showing the previous page.
     */
    @Test
    fun `the page is mirrored before the write and the verdict after it`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val mirror = ProjectionMirror()
        val observed = mutableListOf<String>()
        projector.onBeforeWrite = {
            val state = mirror.state.value
            observed += if (state is MirrorState.Projecting) {
                "mounted:${state.page.pageName}/${state.delivery::class.simpleName}"
            } else {
                "notMounted:${state::class.simpleName}"
            }
        }
        manager(projector, projectionMirror = mirror).start(input, backgroundScope)

        input.enterMode(DashboardMode.SAB_MODE)

        assertEquals(listOf("mounted:RootMenu/InFlight"), observed)
        assertEquals(
            MirrorDelivery.Confirmed,
            (mirror.state.value as MirrorState.Projecting).delivery
        )
    }

    @Test
    fun `a rejected frame leaves the mirror painted and marked stale`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        projector.failWith = ProjectionRejectedException(status = 2, errorPosition = 7)
        val input = FakeInputDispatcher()
        val mirror = ProjectionMirror()
        manager(projector, projectionMirror = mirror).start(input, backgroundScope)

        input.enterMode(DashboardMode.SAB_MODE)

        val state = mirror.state.value as MirrorState.Projecting
        assertEquals("RootMenu", state.page.pageName)
        assertEquals(MirrorDelivery.Rejected(status = 2, errorPosition = 7), state.delivery)
    }

    @Test
    fun `the indicator collector feeds the mirrored status bar and stops with the engine`() =
        testScope.runTest {
            val projector = RecordingScreenProjector()
            val input = FakeInputDispatcher()
            val mirror = ProjectionMirror()
            val indicators = ActiveIndicatorsPublisher(projector)
            val stateManager =
                manager(projector, projectionMirror = mirror, activeIndicators = indicators)
            stateManager.start(input, backgroundScope)
            input.enterMode(DashboardMode.SAB_MODE)

            indicators.set(ActiveIndicator.MUSIC, enabled = true)

            assertEquals(
                setOf(ActiveIndicator.MUSIC),
                (mirror.state.value as MirrorState.Projecting).indicators
            )

            stateManager.stop()
            indicators.set(ActiveIndicator.NAVIGATION, enabled = true)

            assertEquals("No session, no mirror", MirrorState.Idle, mirror.state.value)
        }

    @Test
    fun `stop leaves the mirror idle`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val mirror = ProjectionMirror()
        val stateManager = manager(projector, projectionMirror = mirror)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)

        stateManager.stop()

        assertEquals(MirrorState.Idle, mirror.state.value)
    }

    /**
     * A tap on the mirror is indistinguishable from a handlebar press: same event, same collector,
     * same frame. `BaseStateManager` never reads `source` — if it did there would be two paths, and
     * with two paths the app and the cluster could disagree again.
     */
    @Test
    fun `a press from the mirror travels the very same path as one from the handlebar`() =
        testScope.runTest {
            val fromHandlebar = RecordingScreenProjector()
            val handlebarInput = FakeInputDispatcher()
            manager(fromHandlebar).start(handlebarInput, backgroundScope)
            handlebarInput.enterMode(DashboardMode.SAB_MODE)
            handlebarInput.press(FunctionId.NEXT, InputSource.HANDLEBAR)

            val fromMirror = RecordingScreenProjector()
            val mirrorInput = FakeInputDispatcher()
            manager(fromMirror).start(mirrorInput, backgroundScope)
            mirrorInput.enterMode(DashboardMode.SAB_MODE)
            mirrorInput.injectMirrorPress(FunctionId.NEXT)

            assertEquals(listOf(FunctionId.NEXT), mirrorInput.injectedPresses)
            assertEquals(
                fromHandlebar.frames.map { (it as ProjectionFrame.MenuScreen).headerText },
                fromMirror.frames.map { (it as ProjectionFrame.MenuScreen).headerText }
            )
        }

    // ---------------------------------------------------------------------------------------------
    // Interruptions
    // ---------------------------------------------------------------------------------------------

    /**
     * Drives claims on the screen from the test body.
     *
     * Deliberately a `SharedFlow` and not a `StateFlow`: emitting the *same* page twice is the case
     * that matters — a call going from `RINGING` to `ACTIVE` — and a `StateFlow` would conflate it
     * away. In production the conflation is harmless for the same reason it is wrong here: the
     * upstream `CallInfo` values differ, so `map` emits twice.
     */
    private class TestInterruptSource : InterruptSource {

        private val claims =
            MutableSharedFlow<PageProvider?>(replay = 1, extraBufferCapacity = 8)

        override val interrupting: Flow<PageProvider?> = claims

        suspend fun claim(page: PageProvider?) = claims.emit(page)
    }

    @Test
    fun `an interruption is mounted on the interrupt stack and projected`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val mirror = ProjectionMirror()
        val interrupts = TestInterruptSource()
        val stateManager = manager(projector, projectionMirror = mirror, interruptSource = interrupts)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        val framesBefore = projector.frames.size

        interrupts.claim(StubPageProvider("CallPage", liveScreen()))

        assertTrue("The claim belongs on the interrupt stack", stateManager.stack.hasInterrupt)
        assertEquals(framesBefore + 1, projector.frames.size)
        assertEquals(
            "CallPage",
            (mirror.state.value as MirrorState.Projecting).page.pageName
        )
        assertEquals(
            listOf("RootMenu", "CallPage"),
            (mirror.state.value as MirrorState.Projecting).trail
        )
    }

    /**
     * `RINGING` to `ACTIVE` is the same page with different content, so it has to redraw without
     * stacking a second copy the rider would then have to dismiss twice.
     */
    @Test
    fun `the same interruption again invalidates instead of mounting a second copy`() =
        testScope.runTest {
            val projector = RecordingScreenProjector()
            val input = FakeInputDispatcher()
            val interrupts = TestInterruptSource()
            val stateManager = manager(projector, interruptSource = interrupts)
            stateManager.start(input, backgroundScope)
            input.enterMode(DashboardMode.SAB_MODE)
            val call = StubPageProvider("CallPage", liveScreen())
            interrupts.claim(call)
            val sizeAfterFirst = stateManager.stack.size
            val framesAfterFirst = projector.frames.size

            interrupts.claim(call)

            assertEquals("No second copy", sizeAfterFirst, stateManager.stack.size)
            assertEquals("But it does redraw", framesAfterFirst + 1, projector.frames.size)
        }

    /**
     * A second claimant replaces the first one; it does not pile on top of it.
     *
     * `PriorityInterruptSource` hands over whichever source outranks the rest, and it does **not**
     * emit a `null` in between: a message claiming the screen and then a call arriving is one
     * emission after another. Reading that as "another interruption" left the first page underneath
     * the second, so BACK walked down through a fossil the rider had already dealt with.
     *
     * It went unseen while each source contributed exactly one page. The prompt page pushes the
     * reading on the same stack, which makes the fossils reachable.
     */
    @Test
    fun `a different claimant replaces the interruption instead of stacking on it`() =
        testScope.runTest {
            val projector = RecordingScreenProjector()
            val input = FakeInputDispatcher()
            val interrupts = TestInterruptSource()
            val stateManager = manager(projector, interruptSource = interrupts)
            stateManager.start(input, backgroundScope)
            input.enterMode(DashboardMode.SAB_MODE)
            interrupts.claim(StubPageProvider("MessagePromptPage", liveScreen()))
            val sizeWithOneClaim = stateManager.stack.size

            interrupts.claim(StubPageProvider("CallPage", liveScreen()))

            assertEquals(
                "the call replaces the prompt, it does not bury it",
                sizeWithOneClaim,
                stateManager.stack.size
            )
            assertEquals("CallPage", stateManager.stack.peek()?.name)
        }

    /**
     * And the pages the replaced claimant had pushed go with it.
     *
     * `Play` on the prompt pushes the reading onto the same interrupt stack, so a call arriving
     * mid-message finds two pages there, not one. Leaving either behind puts the rider one BACK
     * press away from a message page belonging to a claim that is over.
     */
    @Test
    fun `replacing a claimant drops the subpages it had pushed`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val interrupts = TestInterruptSource()
        val read = StubPageProvider("MessagePage", liveScreen())
        val prompt = StubPageProvider(
            "MessagePromptPage",
            liveScreen(),
            inputResult = { functionId ->
                if (functionId == FunctionId.SELECT) {
                    NavigationAction.Next(read)
                } else {
                    NavigationAction.None
                }
            }
        )
        val stateManager = manager(projector, interruptSource = interrupts)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        interrupts.claim(prompt)
        input.press(FunctionId.SELECT)
        assertEquals("MessagePage", stateManager.stack.peek()?.name)

        interrupts.claim(
            StubPageProvider(
                "CallPage",
                liveScreen(),
                inputResult = { functionId ->
                    if (functionId == FunctionId.BACK) NavigationAction.Pop else NavigationAction.None
                }
            )
        )
        input.press(FunctionId.BACK)

        assertEquals(
            "BACK off the call lands on the root menu, not on a message that is over",
            "RootMenu",
            stateManager.stack.peek()?.name
        )
    }

    /**
     * `Play` on an interrupting page puts the reading on the interrupt stack and not underneath the
     * carousel: `NavigationStack.push` writes to whichever stack is active.
     */
    @Test
    fun `a subpage pushed from an interruption stays on the interrupt stack`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val interrupts = TestInterruptSource()
        val read = StubPageProvider("MessagePage", liveScreen())
        val prompt = StubPageProvider(
            "MessagePromptPage",
            liveScreen(),
            inputResult = { functionId ->
                if (functionId == FunctionId.SELECT) {
                    NavigationAction.Next(read)
                } else {
                    NavigationAction.None
                }
            }
        )
        val stateManager = manager(projector, interruptSource = interrupts)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        interrupts.claim(prompt)

        input.press(FunctionId.SELECT)

        assertTrue(stateManager.stack.hasInterrupt)
        assertEquals("MessagePage", stateManager.stack.peek()?.name)
    }

    @Test
    fun `withdrawing an interruption gives back the page underneath`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val interrupts = TestInterruptSource()
        val stateManager = manager(projector, interruptSource = interrupts)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        interrupts.claim(StubPageProvider("CallPage", liveScreen()))

        interrupts.claim(null)

        assertFalse(stateManager.stack.hasInterrupt)
        assertTrue(
            "The root menu comes back on its own",
            projector.frames.last() is ProjectionFrame.MenuScreen
        )
    }

    /**
     * The claim is mounted whatever the mode, so the page is already there when the cluster hands
     * the screen over; `projectActivePage` is what declines to write outside SAB mode.
     */
    @Test
    fun `an interruption raised in HU_MODE is mounted without writing a frame`() =
        testScope.runTest {
            val projector = RecordingScreenProjector()
            val input = FakeInputDispatcher()
            val interrupts = TestInterruptSource()
            val stateManager = manager(projector, interruptSource = interrupts)
            stateManager.start(input, backgroundScope)

            interrupts.claim(StubPageProvider("CallPage", liveScreen()))

            assertTrue(stateManager.stack.hasInterrupt)
            assertEquals("Nothing is written while the tachometer owns the screen", 0, projector.frames.size)

            input.enterMode(DashboardMode.SAB_MODE)

            assertTrue(projector.frames.last() is ProjectionFrame.LiveScreen)
        }

    /**
     * An interruption raised before the screen was ever handed over leaves nothing on the default
     * stack, so withdrawing it would leave the cluster stuck on the frame of a page that is gone.
     * The root goes up instead, for the same reason `pop` never empties the default stack.
     */
    @Test
    fun `withdrawing the last page falls back to the root menu`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val interrupts = TestInterruptSource()
        val stateManager = manager(projector, interruptSource = interrupts)
        stateManager.start(input, backgroundScope)
        interrupts.claim(StubPageProvider("CallPage", liveScreen()))
        input.enterMode(DashboardMode.SAB_MODE)

        interrupts.claim(null)

        assertEquals(1, stateManager.stack.size)
        assertTrue(projector.frames.last() is ProjectionFrame.MenuScreen)
    }

    @Test
    fun `the interrupting page is the one that receives the press`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val interrupts = TestInterruptSource()
        val pressed = mutableListOf<FunctionId>()
        val stateManager = manager(projector, interruptSource = interrupts)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        interrupts.claim(
            StubPageProvider("CallPage", liveScreen(), inputResult = { functionId ->
                pressed += functionId
                NavigationAction.None
            })
        )

        input.press(FunctionId.SELECT)

        assertEquals(listOf(FunctionId.SELECT), pressed)
    }

    @Test
    fun `stop cancels the interrupt collector`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val interrupts = TestInterruptSource()
        val stateManager = manager(projector, interruptSource = interrupts)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        stateManager.stop()
        val framesAfterStop = projector.frames.size

        interrupts.claim(StubPageProvider("CallPage", liveScreen()))

        assertEquals(framesAfterStop, projector.frames.size)
        assertFalse(stateManager.stack.hasInterrupt)
    }

    private fun liveScreen(): ProjectionFrame.LiveScreen =
        LiveScreenPresets.music(
            requestId = 1,
            header = "Music",
            title = "Yesterday",
            artist = "The Beatles",
            playing = true
        )
    // -------------------------------------------------------------------------------------------
    // Invalidations / Redraws
    // -------------------------------------------------------------------------------------------

    @Test
    fun `montar una página escribe exactamente un frame`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val invalidations = MutableSharedFlow<Unit>()
        val page = StubPageProvider("music", liveScreen(), invalidations = invalidations)
        val stateManager = manager(projector, page)
        
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MUSIC)) { input.press(FunctionId.NEXT) }
        val framesBeforeMount = projector.frames.size
        
        input.press(FunctionId.SELECT) // Monta la página
        
        assertEquals("Debe escribir UN frame por el montaje", framesBeforeMount + 1, projector.frames.size)
    }

    @Test
    fun `mover la fuente de la página montada la repinta`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val invalidations = MutableSharedFlow<Unit>()
        val page = StubPageProvider("music", liveScreen(), invalidations = invalidations)
        val stateManager = manager(projector, page)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MUSIC)) { input.press(FunctionId.NEXT) }
        input.press(FunctionId.SELECT) // Monta
        val framesBeforeTick = projector.frames.size

        invalidations.emit(Unit)

        assertEquals("Un tick del flow escribe un frame más", framesBeforeTick + 1, projector.frames.size)
    }

    @Test
    fun `la página de debajo deja de repintarse al apilar otra encima`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val invalidations = MutableSharedFlow<Unit>()
        val page = StubPageProvider(
            name = "music", 
            frame = liveScreen(), 
            invalidations = invalidations,
            inputResult = { NavigationAction.Next(StubPageProvider("other", liveScreen())) }
        )
        val stateManager = manager(projector, page)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MUSIC)) { input.press(FunctionId.NEXT) }
        input.press(FunctionId.SELECT) // Monta 'music'
        input.press(FunctionId.SELECT) // Apila 'other' encima
        val framesBeforeTick = projector.frames.size

        invalidations.emit(Unit) // Tick de la página tapada

        assertEquals("No debe pintar nada si la página ya no está en la cima", framesBeforeTick, projector.frames.size)
    }

    @Test
    fun `stop cancela el colector de repintado`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val invalidations = MutableSharedFlow<Unit>()
        val page = StubPageProvider("music", liveScreen(), invalidations = invalidations)
        val stateManager = manager(projector, page)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MUSIC)) { input.press(FunctionId.NEXT) }
        input.press(FunctionId.SELECT)
        
        stateManager.stop()
        val framesBeforeTick = projector.frames.size

        invalidations.emit(Unit)

        assertEquals("Mover la fuente tras stop() no escribe nada", framesBeforeTick, projector.frames.size)
    }

    // -------------------------------------------------------------------------------------------
    // The voice of a mounted page
    // -------------------------------------------------------------------------------------------

    /**
     * A page with something to say. Built around the same conflated, replay-less channel the real
     * one uses, because what is under test here is what that choice buys: the script is delivered
     * once, and a second one replaces whatever is still being said.
     */
    private class SpeakingStubPage(
        override val name: String,
        private val frame: ProjectionFrame,
        private val inputResult: (FunctionId) -> NavigationAction = { NavigationAction.None }
    ) : SpeakingPage {

        private val scripts = Channel<List<SpeechRequest>>(Channel.CONFLATED)

        override val speech: Flow<List<SpeechRequest>> = scripts.receiveAsFlow()

        override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN

        override val invalidations: Flow<Unit> = emptyFlow()

        fun offer(vararg tokens: SpeechToken) {
            scripts.trySend(tokens.map { SpeechRequest(it, SpeechArg.Text("x").repeatFor(it)) })
        }

        override fun render(requestId: Int): ProjectionFrame = frame

        override fun onInput(functionId: FunctionId): NavigationAction = inputResult(functionId)

        override fun mirror(): MirrorPage =
            mirrorPageOf(name, render(MIRROR_REQUEST_ID), actions = emptyList())

        private fun SpeechArg.repeatFor(token: SpeechToken): List<SpeechArg> =
            List(token.arity) { this }
    }

    /**
     * Says everything but the carousel's own narration, which resolves to blank and is therefore
     * never handed to the synthesizer. The tests below are about the page under test; what the
     * carousel says on the way there and back is pinned down by the carousel tests further down.
     */
    private fun voiceOf(synthesizer: MockSpeechSynthesizer) = PageVoice(
        { request -> if (request.token.name.startsWith("ROOT_")) "" else "said:${request.token}" },
        synthesizer,
        sessionScope = testScope.backgroundScope
    )

    private fun narratingVoiceOf(synthesizer: MockSpeechSynthesizer) =
        PageVoice({ request -> "said:${request.token}" }, synthesizer, sessionScope = testScope.backgroundScope)

    private suspend fun FakeInputDispatcher.openMusic() {
        enterMode(DashboardMode.SAB_MODE)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MUSIC)) { press(FunctionId.NEXT) }
        press(FunctionId.SELECT)
    }

    @Test
    fun `mounting a speaking page says its script`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val synthesizer = MockSpeechSynthesizer()
        val page = SpeakingStubPage("speaking", liveScreen())
        page.offer(SpeechToken.WEATHER_FORECAST_MESSAGE, SpeechToken.WEATHER_CURRENT_LINE)
        manager(projector, page, pageVoice = voiceOf(synthesizer)).start(input, backgroundScope)

        input.openMusic()

        assertEquals(
            listOf("said:WEATHER_FORECAST_MESSAGE", "said:WEATHER_CURRENT_LINE"),
            synthesizer.utterances.map { it.text }
        )
    }

    @Test
    fun `a page that does not speak starts no voice`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val synthesizer = MockSpeechSynthesizer()
        manager(
            projector,
            StubPageProvider("music", liveScreen()),
            pageVoice = voiceOf(synthesizer)
        ).start(input, backgroundScope)

        input.openMusic()

        assertTrue(synthesizer.utterances.isEmpty())
    }

    /**
     * BACK cuts the utterance without a single line in the engine that talks about cutting: it
     * moves the stack, the stack changes the mounted page, and the speech job dies with it.
     */
    @Test
    fun `leaving a speaking page cuts the voice`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val synthesizer = MockSpeechSynthesizer().apply { speaksUntilStopped = true }
        val page = SpeakingStubPage(
            name = "speaking",
            frame = liveScreen(),
            inputResult = { if (it == FunctionId.BACK) NavigationAction.Pop else NavigationAction.None }
        )
        page.offer(SpeechToken.WEATHER_FORECAST_MESSAGE, SpeechToken.WEATHER_CURRENT_LINE)
        manager(projector, page, pageVoice = voiceOf(synthesizer)).start(input, backgroundScope)
        input.openMusic()

        input.press(FunctionId.BACK)

        assertEquals(listOf("said:WEATHER_FORECAST_MESSAGE"), synthesizer.utterances.map { it.text })
        assertEquals(1, synthesizer.stopCalls)
    }

    @Test
    fun `an interruption cuts the voice of the page it covers`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val interrupts = TestInterruptSource()
        val synthesizer = MockSpeechSynthesizer().apply { speaksUntilStopped = true }
        val page = SpeakingStubPage("speaking", liveScreen())
        page.offer(SpeechToken.WEATHER_FORECAST_MESSAGE, SpeechToken.WEATHER_CURRENT_LINE)
        manager(
            projector,
            page,
            interruptSource = interrupts,
            pageVoice = voiceOf(synthesizer)
        ).start(input, backgroundScope)
        input.openMusic()

        interrupts.claim(StubPageProvider("call", liveScreen()))

        assertEquals(listOf("said:WEATHER_FORECAST_MESSAGE"), synthesizer.utterances.map { it.text })
        assertEquals(1, synthesizer.stopCalls)
    }

    /**
     * `RETAIN` keeps the frame and the state of the page, and the voice is neither: an utterance
     * cut in half by a phone call and then repeated whole when it ends is the opposite of what the
     * rider wants. It falls out of the channel having no replay, not out of a flag.
     */
    @Test
    fun `resuming after an interruption does not say the script again`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val interrupts = TestInterruptSource()
        val synthesizer = MockSpeechSynthesizer()
        val page = SpeakingStubPage("speaking", liveScreen())
        page.offer(SpeechToken.WEATHER_FORECAST_MESSAGE)
        manager(
            projector,
            page,
            interruptSource = interrupts,
            pageVoice = voiceOf(synthesizer)
        ).start(input, backgroundScope)
        input.openMusic()
        interrupts.claim(StubPageProvider("call", liveScreen()))

        interrupts.claim(null)

        assertEquals(listOf("said:WEATHER_FORECAST_MESSAGE"), synthesizer.utterances.map { it.text })
    }

    /**
     * SELECT on a page that exists to speak means "say it again", so a script arriving while one is
     * still being said replaces it rather than queueing behind it — `collectLatest` and not
     * `collect`.
     */
    @Test
    fun `a second script cancels the one still being said`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val synthesizer = MockSpeechSynthesizer().apply { speaksUntilStopped = true }
        val page = SpeakingStubPage("speaking", liveScreen())
        page.offer(SpeechToken.WEATHER_FORECAST_MESSAGE, SpeechToken.WEATHER_CURRENT_LINE)
        manager(projector, page, pageVoice = voiceOf(synthesizer)).start(input, backgroundScope)
        input.openMusic()

        page.offer(SpeechToken.MESSAGE_READ)

        assertEquals(
            listOf("said:WEATHER_FORECAST_MESSAGE", "said:MESSAGE_READ"),
            synthesizer.utterances.map { it.text }
        )
    }

    /**
     * The cluster can take the screen back mid-sentence, and it does: a warning on the dashboard —
     * the oil pressure one, with the engine stopped — makes the BTU keep the screen until the rider
     * dismisses it. Hearing a page he can no longer see
     * is the same defect as hearing one he has already left, so the voice goes quiet.
     */
    @Test
    fun `the cluster taking the screen back cuts the voice`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val synthesizer = MockSpeechSynthesizer().apply { speaksUntilStopped = true }
        val page = SpeakingStubPage("speaking", liveScreen())
        page.offer(SpeechToken.WEATHER_FORECAST_MESSAGE, SpeechToken.WEATHER_CURRENT_LINE)
        manager(projector, page, pageVoice = voiceOf(synthesizer)).start(input, backgroundScope)
        input.openMusic()

        input.enterMode(DashboardMode.HU_MODE)

        assertEquals(listOf("said:WEATHER_FORECAST_MESSAGE"), synthesizer.utterances.map { it.text })
        assertEquals(1, synthesizer.stopCalls)
    }

    /**
     * And it does not pick up where it left off when the screen comes back: the same rule an
     * interruption follows, and for the same reason — a sentence cut in half and then replayed
     * whole is worse than one nobody finished.
     */
    @Test
    fun `the voice does not start again when the screen comes back`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val synthesizer = MockSpeechSynthesizer().apply { speaksUntilStopped = true }
        val page = SpeakingStubPage("speaking", liveScreen())
        page.offer(SpeechToken.WEATHER_FORECAST_MESSAGE, SpeechToken.WEATHER_CURRENT_LINE)
        manager(projector, page, pageVoice = voiceOf(synthesizer)).start(input, backgroundScope)
        input.openMusic()
        input.enterMode(DashboardMode.HU_MODE)

        input.enterMode(DashboardMode.SAB_MODE)

        assertEquals(listOf("said:WEATHER_FORECAST_MESSAGE"), synthesizer.utterances.map { it.text })
    }

    /**
     * But the page must not be left mute for good, which is the trap of merely cancelling the job
     * and remembering the page as still attached: after the round trip the rider presses the one
     * key of a page that exists to speak, and it has to speak.
     */
    @Test
    fun `a page can still be asked to speak after the screen comes back`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val synthesizer = MockSpeechSynthesizer()
        val page = SpeakingStubPage("speaking", liveScreen())
        page.offer(SpeechToken.WEATHER_FORECAST_MESSAGE)
        manager(projector, page, pageVoice = voiceOf(synthesizer)).start(input, backgroundScope)
        input.openMusic()
        input.enterMode(DashboardMode.HU_MODE)
        input.enterMode(DashboardMode.SAB_MODE)

        page.offer(SpeechToken.MESSAGE_READ)

        assertEquals(
            listOf("said:WEATHER_FORECAST_MESSAGE", "said:MESSAGE_READ"),
            synthesizer.utterances.map { it.text }
        )
    }

    @Test
    fun `stop cancels the voice`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val synthesizer = MockSpeechSynthesizer().apply { speaksUntilStopped = true }
        val page = SpeakingStubPage("speaking", liveScreen())
        page.offer(SpeechToken.WEATHER_FORECAST_MESSAGE, SpeechToken.WEATHER_CURRENT_LINE)
        val stateManager = manager(projector, page, pageVoice = voiceOf(synthesizer))
        stateManager.start(input, backgroundScope)
        input.openMusic()

        stateManager.stop()

        assertEquals(1, synthesizer.stopCalls)
        assertEquals(1, synthesizer.utterances.size)
    }

    /**
     * The branch the other five engine tests that build a manager by hand run through: no voice
     * given, nothing said, and nothing thrown either.
     */
    @Test
    fun `an engine with no voice mounts a speaking page in silence`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val page = SpeakingStubPage("speaking", liveScreen())
        page.offer(SpeechToken.WEATHER_FORECAST_MESSAGE)
        val stateManager = manager(projector, page)
        stateManager.start(input, backgroundScope)

        input.openMusic()

        assertEquals("The page is mounted all the same", 2, stateManager.stack.size)
    }

    @Test
    fun `una interrupción montada se repinta con su propia fuente`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val interrupts = TestInterruptSource()
        val invalidations = MutableSharedFlow<Unit>()
        val callPage = StubPageProvider("call", liveScreen(), invalidations = invalidations)
        val stateManager = manager(projector, interruptSource = interrupts)
        stateManager.start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE) // Monta el RootMenu
        
        interrupts.claim(callPage) // Monta la interrupción
        val framesBeforeTick = projector.frames.size

        invalidations.emit(Unit) // Tick de la interrupción

        assertEquals("La interrupción se repinta con su propia fuente", framesBeforeTick + 1, projector.frames.size)
    }

    // -------------------------------------------------------------------------------------------
    // The work of a mounted page
    // -------------------------------------------------------------------------------------------

    /**
     * A page that does something while it is mounted. It suspends until the test releases it, which
     * is what makes "cancelled the instant it stops being mounted" observable.
     */
    private class WorkingStubPage(
        override val name: String,
        private val frame: ProjectionFrame,
        private val outcome: () -> NavigationAction = { NavigationAction.None },
        private val inputResult: (FunctionId) -> NavigationAction = { NavigationAction.None }
    ) : WorkingPage {

        private val released = CompletableDeferred<Unit>()

        var workCalls = 0
            private set
        var cancelled = false
            private set
        var finished = false
            private set

        /** Lets the work run to its end, and with it the action it asked for. */
        fun release() = released.complete(Unit)

        override suspend fun work(): NavigationAction {
            workCalls++
            try {
                released.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
            finished = true
            return outcome()
        }

        override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN

        override val invalidations: Flow<Unit> = emptyFlow()

        override fun render(requestId: Int): ProjectionFrame = frame

        override fun onInput(functionId: FunctionId): NavigationAction = inputResult(functionId)

        override fun mirror(): MirrorPage =
            mirrorPageOf(name, render(MIRROR_REQUEST_ID), actions = emptyList())
    }

    @Test
    fun `mounting a working page runs its work`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val page = WorkingStubPage("working", liveScreen())
        manager(projector, page).start(input, backgroundScope)

        input.openMusic()

        assertEquals(1, page.workCalls)
    }

    /**
     * BACK, MENU, an incoming call and the cluster taking the screen back all cut the work without
     * a single line here that talks about cutting - the same mechanism the voice rides on. It is
     * what closes the microphone when the rider leaves the dictation page.
     */
    @Test
    fun `leaving a working page cancels its work`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val page = WorkingStubPage(
            name = "working",
            frame = liveScreen(),
            inputResult = { if (it == FunctionId.BACK) NavigationAction.Pop else NavigationAction.None }
        )
        manager(projector, page).start(input, backgroundScope)
        input.openMusic()

        input.press(FunctionId.BACK)

        assertTrue("the work must be cancelled before the next page is mounted", page.cancelled)
        assertFalse(page.finished)
    }

    @Test
    fun `an interruption cancels the work of the page it covers`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val interrupts = TestInterruptSource()
        val page = WorkingStubPage("working", liveScreen())
        manager(projector, page, interruptSource = interrupts).start(input, backgroundScope)
        input.openMusic()

        interrupts.claim(StubPageProvider("call", liveScreen()))

        assertTrue("a call mid-dictation must close the microphone", page.cancelled)
    }

    @Test
    fun `the cluster taking the screen back cancels the work`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val page = WorkingStubPage("working", liveScreen())
        manager(projector, page).start(input, backgroundScope)
        input.openMusic()

        input.enterMode(DashboardMode.HU_MODE)

        assertTrue(page.cancelled)
    }

    @Test
    fun `stop cancels the work`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val page = WorkingStubPage("working", liveScreen())
        val stateManager = manager(projector, page)
        stateManager.start(input, backgroundScope)
        input.openMusic()

        stateManager.stop()

        assertTrue(page.cancelled)
    }

    /**
     * The reentrancy of a page's work, and the reason the action travels by channel.
     *
     * Applying it re-enters `projectActivePage`, which cancels the work job - so applied from
     * inside the job that produced it, the coroutine would die at its next suspension and the frame
     * would never reach the cluster. The page would be gone from the stack with the cluster still
     * showing the old one. This test is the whole of that trap: the frame has to come out.
     */
    @Test
    fun `projects the frame of the action its own work asked for`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val page = WorkingStubPage(
            name = "working",
            frame = liveScreen(),
            outcome = { NavigationAction.Pop }
        )
        val stateManager = manager(projector, page)
        stateManager.start(input, backgroundScope)
        input.openMusic()
        val framesBefore = projector.frames.size

        page.release()
        runCurrent()

        assertTrue("the work must have run to its end", page.finished)
        assertEquals("the page it popped back to must be projected", 1, stateManager.stack.size)
        assertEquals(framesBefore + 1, projector.frames.size)
        assertTrue(projector.frames.last() is ProjectionFrame.MenuScreen)
    }

    /**
     * The channel is RENDEZVOUS and mounting another page cancels workJob, so an action
     * produced by a page that has just been covered never lands.
     * This is what keeps a call arriving mid-dial from applying a Replace to the wrong stack.
     */
    @Test
    fun `should apply nothing when an interruption covers a page mid-work`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val interrupts = TestInterruptSource()
        val replacement = StubPageProvider("replacement", liveScreen())
        val page = WorkingStubPage(
            name = "working",
            frame = liveScreen(),
            outcome = { NavigationAction.Replace(replacement) }
        )
        val stateManager = manager(projector, page, interruptSource = interrupts)
        stateManager.start(input, backgroundScope)
        input.openMusic()

        interrupts.claim(StubPageProvider("call", liveScreen()))

        assertTrue("a call mid-work must cancel the work coroutine", page.cancelled)

        page.release()
        runCurrent()

        assertFalse("the work never finishes once cancelled", page.finished)
        assertEquals(
            listOf("RootMenu", "working", "call"),
            stateManager.stack.snapshot().map { it.name }
        )
        assertEquals("call", stateManager.stack.peek()?.name)
    }

    /** A redraw is not a remount: the work of a page runs once per mount and never per frame. */
    @Test
    fun `a redraw does not run the work again`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val page = WorkingStubPage("working", liveScreen())
        val stateManager = manager(projector, page)
        stateManager.start(input, backgroundScope)
        input.openMusic()

        stateManager.invalidate()

        assertEquals(1, page.workCalls)
    }

    /** A page that only speaks has no work, and one that only works has nothing to say. */
    @Test
    fun `a page that only speaks starts no work`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val synthesizer = MockSpeechSynthesizer()
        val page = SpeakingStubPage("speaking", liveScreen())
        page.offer(SpeechToken.WEATHER_FORECAST_MESSAGE)
        manager(projector, page, pageVoice = voiceOf(synthesizer)).start(input, backgroundScope)

        input.openMusic()

        assertEquals(listOf("said:WEATHER_FORECAST_MESSAGE"), synthesizer.utterances.map { it.text })
    }

    @Test
    fun `a page that only works says nothing`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val synthesizer = MockSpeechSynthesizer()
        val page = WorkingStubPage("working", liveScreen())
        manager(projector, page, pageVoice = voiceOf(synthesizer)).start(input, backgroundScope)

        input.openMusic()

        assertEquals(1, page.workCalls)
        assertTrue(synthesizer.utterances.isEmpty())
    }

    @Test
    fun stopReleasesPageVoiceFocus() = testScope.runTest {
        var abandonCount = 0
        val audioFocus = object : AudioFocus {
            override fun request(kind: AudioFocusKind) = AudioFocusLease { abandonCount++ }
        }
        val voice = PageVoice(
            resolver = { "" },
            synthesizer = MockSpeechSynthesizer(),
            audioFocus = audioFocus,
            sessionScope = backgroundScope
        )
        voice.say(listOf(SpeechRequest(SpeechToken.ROOT_MUSIC)))
        assertEquals(0, abandonCount)

        val projector = RecordingScreenProjector()
        val stateManager = manager(projector, pageVoice = voice)
        stateManager.stop()
        assertEquals(1, abandonCount)
    }

    // -------------------------------------------------------------------------------------------
    // The carousel's narration: derived from its focus, so every remount says where the rider is
    // -------------------------------------------------------------------------------------------

    private fun silentPageBackOnBack() = SpeakingStubPage(
        name = "music",
        frame = liveScreen(),
        inputResult = { if (it == FunctionId.BACK) NavigationAction.Pop else NavigationAction.None }
    )

    @Test
    fun `going back to the carousel announces the entry the rider left from`() = testScope.runTest {
        val synthesizer = MockSpeechSynthesizer()
        val input = FakeInputDispatcher()
        manager(RecordingScreenProjector(), silentPageBackOnBack(), pageVoice = narratingVoiceOf(synthesizer))
            .start(input, backgroundScope)
        input.openMusic()
        val beforeBack = synthesizer.utterances.size

        input.press(FunctionId.BACK)

        assertEquals(listOf("said:ROOT_MUSIC"), synthesizer.utterances.drop(beforeBack).map { it.text })
    }

    @Test
    fun `MENU on the carousel says the entry again`() = testScope.runTest {
        val synthesizer = MockSpeechSynthesizer()
        val input = FakeInputDispatcher()
        manager(RecordingScreenProjector(), pageVoice = narratingVoiceOf(synthesizer)).start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        input.press(FunctionId.NEXT)
        val beforeMenu = synthesizer.utterances.size

        input.press(FunctionId.MENU)

        assertEquals(listOf("said:ROOT_NAVIGATION"), synthesizer.utterances.drop(beforeMenu).map { it.text })
    }

    @Test
    fun `the cluster giving the screen back announces the carousel entry again`() = testScope.runTest {
        val synthesizer = MockSpeechSynthesizer()
        val input = FakeInputDispatcher()
        manager(RecordingScreenProjector(), pageVoice = narratingVoiceOf(synthesizer)).start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        input.press(FunctionId.NEXT)
        input.enterMode(DashboardMode.HU_MODE)
        val beforeReturn = synthesizer.utterances.size

        input.enterMode(DashboardMode.SAB_MODE)

        assertEquals(listOf("said:ROOT_NAVIGATION"), synthesizer.utterances.drop(beforeReturn).map { it.text })
    }

    @Test
    fun `the carousel stays quiet at its edge`() = testScope.runTest {
        val synthesizer = MockSpeechSynthesizer()
        val input = FakeInputDispatcher()
        manager(RecordingScreenProjector(), pageVoice = narratingVoiceOf(synthesizer)).start(input, backgroundScope)
        input.enterMode(DashboardMode.SAB_MODE)
        val afterMount = synthesizer.utterances.map { it.text }

        input.press(FunctionId.PREVIOUS)

        assertEquals(listOf("said:ROOT_WEATHER_EMPTY"), afterMount)
        assertEquals(afterMount, synthesizer.utterances.map { it.text })
    }
}
