package com.opensab.cluster

import com.opensab.cluster.mirror.MIRROR_REQUEST_ID
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.cluster.mirror.mirrorPageOf
import com.opensab.domain.audio.Cue
import com.opensab.domain.audio.CuePlayer
import com.opensab.domain.audio.MockCuePlayer
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.input.FakeInputDispatcher
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.RecordingScreenProjector
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private fun liveScreen(requestId: Int = 1): ProjectionFrame.LiveScreen =
    LiveScreenPresets.music(
        requestId = requestId,
        header = "Music",
        title = "Yesterday",
        artist = "The Beatles",
        playing = true
    )

/**
 * The engine's side of the cue vocabulary: `CuingPage`, `cueEmitter()` and the `cueJob` of
 * [BaseStateManager]: one cue per transition, and only one.
 *
 * Bare `runTest {}` and not `TestScope(UnconfinedTestDispatcher())`: under an unconfined
 * dispatcher every plain `launch` already runs synchronously, which would make
 * [CoroutineStart.UNDISPATCHED] indistinguishable from the default and leave the UNDISPATCHED test
 * unable to fail on a regression. `StandardTestDispatcher` (bare `runTest`'s default) is the one dispatcher on
 * which that distinction is observable at all in a single-threaded test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BaseStateManagerCueTest {

    /** A page that says what it is told to say, which is all the engine's contract needs. */
    private class CuingTestPage(
        override val name: String = "Cuing",
        private val inputResult: (FunctionId) -> NavigationAction = { NavigationAction.None }
    ) : CuingPage {

        private val _cues = cueEmitter()

        /** How many times something has started collecting [cues], for C-12's "not restarted". */
        var subscriptions = 0
            private set

        override val cues: Flow<Cue> = _cues.asSharedFlow().onSubscription { subscriptions++ }

        override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN

        override val invalidations: Flow<Unit> = emptyFlow()

        fun say(cue: Cue) = _cues.tryEmit(cue)

        override fun render(requestId: Int): ProjectionFrame = liveScreen(requestId)

        override fun onInput(functionId: FunctionId): NavigationAction = inputResult(functionId)

        override fun mirror(): MirrorPage =
            mirrorPageOf(name, render(MIRROR_REQUEST_ID), actions = emptyList())
    }

    /**
     * A minimal root whose `onInput` a test wires directly, so each of the seven branches of
     * `apply()` can be reached without depending on `RootMenuPageProvider`'s carousel or its
     * `canEnter` gating. `SELECT` pushes [pushed] by default, matching the brief's "the root page
     * answers with Next(...)".
     */
    private fun testRoot(
        pushed: PageProvider = StubPageProvider(
            name = "pushed",
            frame = liveScreen(),
            inputResult = { functionId -> if (functionId == FunctionId.BACK) NavigationAction.Pop else NavigationAction.None }
        ),
        inputResult: (FunctionId) -> NavigationAction = { functionId ->
            when (functionId) {
                FunctionId.SELECT -> NavigationAction.Next(pushed)
                FunctionId.BACK -> NavigationAction.Pop
                FunctionId.MENU -> NavigationAction.Reset
                else -> NavigationAction.None
            }
        }
    ): PageProvider = StubPageProvider(
        name = "root",
        frame = liveScreen(),
        inputResult = inputResult
    )

    private fun manager(
        projector: RecordingScreenProjector,
        cuePlayer: CuePlayer,
        root: PageProvider
    ) = BaseStateManager(
        screenProjector = projector,
        requestIdGenerator = RequestIdGenerator(),
        capabilities = VehicleCapabilitiesHolder(),
        activeIndicators = ActiveIndicatorsPublisher(projector),
        projectionMirror = ProjectionMirror(),
        cuePlayer = cuePlayer,
        rootPageFactory = { root }
    )

    /** What a test presses on, and the state manager underneath it for the bypass tests. */
    private class Engine(
        private val input: FakeInputDispatcher,
        val stateManager: BaseStateManager
    ) {
        suspend fun press(functionId: FunctionId) = input.press(functionId)
    }

    /**
     * Starts a manager, hands the cluster SAB_MODE and drains the scheduler once so every
     * collector `start()` launches (`modeJob`, `eventJob`...) has actually subscribed — a bare
     * `runTest {}` uses `StandardTestDispatcher`, so a plain `launch` only *schedules* until
     * `runCurrent()` runs it, and an event emitted before that subscription is a replay=0 SharedFlow
     * emission nobody was there to hear.
     */
    private suspend fun TestScope.engineWith(
        cuePlayer: CuePlayer,
        scope: CoroutineScope,
        root: PageProvider = testRoot()
    ): Engine {
        val projector = RecordingScreenProjector()
        val input = FakeInputDispatcher()
        val stateManager = manager(projector, cuePlayer, root)
        stateManager.start(input, scope)
        input.enterMode(DashboardMode.SAB_MODE)
        runCurrent()
        return Engine(input, stateManager)
    }

    @Test
    fun `should cue entering a page`() = runTest {
        val player = MockCuePlayer()
        val engine = engineWith(player, backgroundScope)

        engine.press(FunctionId.SELECT)     // the root page answers with Next(...)
        runCurrent()

        assertEquals(listOf(Cue.ENTER), player.played)
    }

    @Test
    fun `should cue leaving a page`() = runTest {
        val player = MockCuePlayer()
        val engine = engineWith(player, backgroundScope)
        engine.press(FunctionId.SELECT)
        runCurrent()
        player.reset()

        engine.press(FunctionId.BACK)
        runCurrent()

        assertEquals(listOf(Cue.LEAVE), player.played)
    }

    @Test
    fun `should stay quiet on a reset`() = runTest {
        val player = MockCuePlayer()
        val pushed = StubPageProvider(
            name = "pushed",
            frame = liveScreen(),
            inputResult = { functionId ->
                if (functionId == FunctionId.MENU) NavigationAction.Reset else NavigationAction.None
            }
        )
        val engine = engineWith(player, backgroundScope, root = testRoot(pushed = pushed))
        engine.press(FunctionId.SELECT) // mount `pushed`, on top of a genuine two-page stack
        runCurrent()
        player.reset()

        engine.press(FunctionId.MENU) // `pushed`'s onInput answers MENU with Reset, unwinding to root
        runCurrent()

        assertEquals(emptyList<Cue>(), player.played)
    }

    @Test
    fun `should stay quiet on an interruption`() = runTest {
        val player = MockCuePlayer()
        val interrupting = StubPageProvider("interrupting", liveScreen())
        val root = testRoot(inputResult = { NavigationAction.Interrupt(interrupting) })
        val engine = engineWith(player, backgroundScope, root = root)

        engine.press(FunctionId.SELECT)
        runCurrent()

        assertEquals(emptyList<Cue>(), player.played)
    }

    @Test
    fun `should stay quiet on a replace`() = runTest {
        val player = MockCuePlayer()
        val replacement = StubPageProvider("replacement", liveScreen())
        val root = testRoot(inputResult = { NavigationAction.Replace(replacement) })
        val engine = engineWith(player, backgroundScope, root = root)

        engine.press(FunctionId.SELECT)
        runCurrent()

        assertEquals(emptyList<Cue>(), player.played)
    }

    @Test
    fun `should stay quiet on a redraw`() = runTest {
        val player = MockCuePlayer()
        val root = testRoot(inputResult = { NavigationAction.Invalidate })
        val engine = engineWith(player, backgroundScope, root = root)

        engine.press(FunctionId.SELECT)
        runCurrent()

        assertEquals(emptyList<Cue>(), player.played)
    }

    @Test
    fun `should stay quiet when the press did nothing`() = runTest {
        val player = MockCuePlayer()
        val engine = engineWith(player, backgroundScope) // root's onInput answers everything else with None

        engine.press(FunctionId.VOLUME_UP)
        runCurrent()

        assertEquals(emptyList<Cue>(), player.played)
    }

    @Test
    fun `should not announce a departure that did not happen`() {
        // `Pop` on the root returns null and moves nothing, so a LEAVE there would announce a
        // departure that did not occur.
        runTest {
            val player = MockCuePlayer()
            val engine = engineWith(player, backgroundScope) // never pushed anything; root is the only page

            engine.press(FunctionId.BACK) // the root's onInput answers BACK with Pop
            runCurrent()

            assertEquals(emptyList<Cue>(), player.played)
        }
    }

    @Test
    fun `should sound what the mounted page declares`() = runTest {
        val player = MockCuePlayer()
        val page = CuingTestPage()
        val engine = engineWith(player, backgroundScope, root = testRoot(pushed = page))
        engine.press(FunctionId.SELECT)
        runCurrent()
        player.reset()

        page.say(Cue.MOVE)
        runCurrent()

        assertEquals(listOf(Cue.MOVE), player.played)
    }

    @Test
    fun `should mount a page that declares no cues`() = runTest {
        val player = MockCuePlayer()
        val plainPage = StubPageProvider("plain", liveScreen())
        val engine = engineWith(player, backgroundScope, root = testRoot(pushed = plainPage))

        engine.press(FunctionId.SELECT)
        runCurrent()

        assertEquals(
            "mounting must succeed even though the page has nothing to say",
            2,
            engine.stateManager.stack.size
        )
        assertEquals("only the engine's own ENTER, nothing from the page itself", listOf(Cue.ENTER), player.played)
    }

    @Test
    fun `should stop listening to a page it has left`() = runTest {
        val player = MockCuePlayer()
        val page = CuingTestPage(
            inputResult = { if (it == FunctionId.BACK) NavigationAction.Pop else NavigationAction.None }
        )
        val engine = engineWith(player, backgroundScope, root = testRoot(pushed = page))
        engine.press(FunctionId.SELECT)
        runCurrent()
        engine.press(FunctionId.BACK)
        runCurrent()
        player.reset()

        page.say(Cue.MOVE)
        runCurrent()

        assertEquals(
            "no collector should still be listening once the page has left",
            emptyList<Cue>(),
            player.played
        )
    }

    @Test
    fun `should not restart the cues on a redraw`() = runTest {
        val player = MockCuePlayer()
        val page = CuingTestPage()
        val engine = engineWith(player, backgroundScope, root = testRoot(pushed = page))
        engine.press(FunctionId.SELECT)
        runCurrent()
        val subscriptionsAfterMount = page.subscriptions
        assertEquals(1, subscriptionsAfterMount)

        engine.stateManager.invalidate() // INVALIDATE does not touch the stack: same page, no remount
        runCurrent()

        assertEquals(
            "a redraw must not cancel and relaunch the cue collector",
            subscriptionsAfterMount,
            page.subscriptions
        )
    }

    @Test
    fun `should not replay the cues of the previous mount`() {
        // `replay = 0`: a cue handed over is not handed over again, so coming back from an
        // interruption resumes the page in silence.
        runTest {
            val player = MockCuePlayer()
            val page = CuingTestPage(
                inputResult = { if (it == FunctionId.BACK) NavigationAction.Pop else NavigationAction.None }
            )
            val engine = engineWith(player, backgroundScope, root = testRoot(pushed = page))
            engine.press(FunctionId.SELECT) // first mount
            runCurrent()
            engine.press(FunctionId.BACK) // unmount: cueJob is cancelled, nobody is listening
            runCurrent()
            player.reset()

            page.say(Cue.MOVE) // emitted with no collector attached: replay=0 drops it for good
            runCurrent()
            engine.press(FunctionId.SELECT) // remount the very same page instance
            runCurrent()

            assertEquals(
                "the stale MOVE must not surface on the new mount, only the engine's own ENTER",
                listOf(Cue.ENTER),
                player.played
            )
        }
    }

    @Test
    fun `should emit exactly one cue per transition`() = runTest {
        val player = MockCuePlayer()
        val page = CuingTestPage()
        val engine = engineWith(player, backgroundScope, root = testRoot(pushed = page))

        engine.press(FunctionId.SELECT)
        runCurrent()

        assertEquals(listOf(Cue.ENTER), player.played)
    }

    @Test
    fun `should not lose the first cue of a mount`() {
        // The dialler seeded with Voice emits LISTEN from `work()`, microseconds after the mount.
        // `cueJob` starts UNDISPATCHED so its subscription is registered before `workJob` is even
        // scheduled.
        runTest {
            val player = MockCuePlayer()
            val engine = engineWith(player, backgroundScope)
            val page = CuingTestPage()

            // Mounted through the public bypass (`stack` + `invalidate()`) rather than through a
            // press: `invalidate()` is a suspend call awaited directly from this very coroutine, so
            // `projectActivePage()` - and with it cueJob's launch - runs inline, with no
            // runCurrent() in between. A real press cannot demonstrate this: it always needs its
            // own runCurrent() to be processed at all, and that single call drains cueJob's launch
            // (scheduled textually before workJob's) to completion before workJob ever runs, which
            // hides the very race UNDISPATCHED exists to close. What follows reproduces that race
            // directly: cueJob's subscription must already exist at the exact instant something
            // emits into `cues`, with no scheduler turn to rescue a merely-scheduled collector.
            engine.stateManager.stack.push(page)
            engine.stateManager.invalidate()

            // Stands in for the dialler's own `_cues.tryEmit(Cue.LISTEN)` inside `work()`: with a
            // plain `launch` for cueJob, no subscriber would exist yet at this exact point and this
            // emission would be lost for good (replay = 0). With CoroutineStart.UNDISPATCHED the
            // subscription is already there, synchronously, before `invalidate()` even returned.
            page.say(Cue.LISTEN)
            runCurrent()

            assertEquals(listOf(Cue.LISTEN), player.played)
        }
    }
}
