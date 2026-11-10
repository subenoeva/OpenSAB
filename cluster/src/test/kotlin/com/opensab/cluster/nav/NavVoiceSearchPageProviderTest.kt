package com.opensab.cluster.nav
import app.cash.turbine.test
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.StubPageProvider
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.audio.Cue
import com.opensab.domain.music.RecordingVolumeSource
import com.opensab.domain.nav.FakeDeviceUnlockSource
import com.opensab.domain.nav.ManeuverType
import com.opensab.domain.nav.MockNavigationLauncher
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.speech.MockSpeechRecognizer
import com.opensab.domain.speech.RecognitionFailure
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class NavVoiceSearchPageProviderTest {

    private val recognizer = MockSpeechRecognizer().apply { listensUntilReleased = true }
    private val launcher = MockNavigationLauncher()
    private val unlocks = FakeDeviceUnlockSource()
    private val instructionFlow = MutableStateFlow<NavInstruction?>(null)
    private val livePage = StubPageProvider("live", ProjectionFrame.RequestSabMode(1))
    private val copy = englishClusterText

    private fun provider(): NavVoiceSearchPageProvider =
        NavVoiceSearchPageProvider(
            recognizer = recognizer,
            launcher = launcher,
            unlockSource = unlocks,
            navInstructionFlow = instructionFlow,
            livePageFactory = { livePage },
            copy = copy
        )

    @Test
    fun `V-NAV-03 successful recognition confirms with speech and coherent action icons`() = runTest {
        val page = provider()

        page.cues.test {
            val workJob = launch { page.work() }
            runCurrent()

            assertEquals(Cue.LISTEN, awaitItem())
            assertEquals(NavVoiceState.Listening, page.state.value)

            page.speech.test {
                assertEquals(emptyList<SpeechRequest>(), awaitItem())

                recognizer.release("Repsol Paseo Castellana")
                runCurrent()

                val speechRequests = awaitItem()
                assertEquals(1, speechRequests.size)
                assertEquals(SpeechToken.NAV_CONFIRM, speechRequests[0].token)
                assertEquals(SpeechArg.Text("Repsol Paseo Castellana"), speechRequests[0].args[0])

                assertEquals(NavVoiceState.Confirming("Repsol Paseo Castellana"), page.state.value)

                val frame = page.render(1) as ProjectionFrame.LiveScreen
                assertEquals("Repsol Paseo Castellana", frame.contentTextLine1)
                assertEquals("", frame.contentTextLine2)
                assertEquals(IconId.LIVE_RETRY, frame.leftActionContentIconId)
                assertEquals(IconId.LIVE_ACCEPT, frame.rightActionContentIconId)

                workJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun `confirming state splits long query into two lines`() = runTest {
        val page = provider()
        val workJob = launch { page.work() }
        runCurrent()

        recognizer.release("Gasolinera Repsol Paseo de la Castellana 120 Madrid")
        runCurrent()

        val frame = page.render(1) as ProjectionFrame.LiveScreen
        assertEquals("Gasolinera Repsol Paseo de la", frame.contentTextLine1)
        assertEquals("Castellana 120 Madrid", frame.contentTextLine2)
        assertEquals(IconId.LIVE_RETRY, frame.leftActionContentIconId)
        assertEquals(IconId.LIVE_ACCEPT, frame.rightActionContentIconId)
        workJob.cancelAndJoin()
    }

    @Test
    fun `V-NAV-04 confirmation launches app, enters loading state, and replaces on TBT arrival`() = runTest {
        val page = provider()
        var actionResult: NavigationAction? = null

        val workJob = launch { actionResult = page.work() }
        runCurrent()

        recognizer.release("Gran Vía Madrid")
        runCurrent()

        page.cues.test {
            // Rider presses SELECT to confirm destination
            val inputAction = page.onInput(FunctionId.SELECT)
            assertEquals(NavigationAction.None, inputAction)
            assertEquals(Cue.SUCCESS, awaitItem())

            assertTrue(page.state.value is NavVoiceState.Launching)
            val loadingFrame = page.render(2) as ProjectionFrame.LiveScreen
            assertEquals(IconId.LOADING, loadingFrame.contentIconId)
            assertEquals("Starting route...", loadingFrame.contentTextLine1)

            // TBT notification arrives from Google Maps
            instructionFlow.value = NavInstruction(
                title = "In 100m",
                streetName = "Gran Vía",
                distanceFormatted = "100m",
                maneuver = ManeuverType.STRAIGHT
            )
            runCurrent()

            assertTrue(actionResult is NavigationAction.Replace)
            assertEquals(livePage, (actionResult as NavigationAction.Replace).page)
            workJob.cancelAndJoin()
        }
    }

    @Test
    fun `V-NAV-05 retry from confirmation face triggers re-listening synchronously`() = runTest {
        val page = provider()
        val workJob = launch { page.work() }
        runCurrent()

        recognizer.release("Destino Erróneo")
        runCurrent()
        assertEquals(NavVoiceState.Confirming("Destino Erróneo"), page.state.value)

        // Press BACK to retry
        page.cues.test {
            val inputAction = page.onInput(FunctionId.BACK)
            assertEquals(NavigationAction.None, inputAction)
            runCurrent()

            assertEquals(NavVoiceState.Listening, page.state.value)
            assertEquals(Cue.LISTEN, awaitItem())
            workJob.cancelAndJoin()
        }
    }

    @Test
    fun `V-NAV-06 silence leads to nothing understood and back pops cleanly without trap`() = runTest {
        val page = provider()
        val workJob = launch { page.work() }
        runCurrent()

        page.cues.test {
            recognizer.release(null) // Silence
            runCurrent()

            assertEquals(Cue.FAILURE, awaitItem())
            assertEquals(NavVoiceState.NothingUnderstood, page.state.value)

            val frame = page.render(3) as ProjectionFrame.LiveScreen
            assertEquals("Nothing understood", frame.contentTextLine1)

            // Press BACK: must pop to menu, not get trapped in infinite listening
            val inputAction = page.onInput(FunctionId.BACK)
            assertEquals(NavigationAction.Pop, inputAction)
            workJob.cancelAndJoin()
        }
    }

    @Test
    fun `V-NAV-07 interruption causes immediate pop on resume`() = runTest {
        val page = provider()

        // First mount starts listening
        val job1 = launch { page.work() }
        runCurrent()
        assertEquals(NavVoiceState.Listening, page.state.value)

        // External interruption (e.g. call) cancels work
        job1.cancelAndJoin()

        // Second mount (call dismissed) must pop immediately
        var resumedAction: NavigationAction? = null
        val job2 = launch { resumedAction = page.work() }
        runCurrent()

        assertEquals(NavigationAction.Pop, resumedAction)
        job2.cancelAndJoin()
    }

    @Test
    fun `ambiguous query watchdog triggers after 15s without TBT instruction`() = runTest {
        val page = provider()
        val workJob = launch { page.work() }
        runCurrent()

        recognizer.release("Gasolinera")
        runCurrent()

        page.cues.test {
            page.onInput(FunctionId.SELECT)
            runCurrent()
            assertEquals(Cue.SUCCESS, awaitItem()) // Launch intent succeeded
            assertTrue(page.state.value is NavVoiceState.Launching)

            // Advance virtual time by 15 seconds without TBT instruction
            advanceTimeBy(15.seconds)
            runCurrent()

            assertEquals(Cue.FAILURE, awaitItem())
            assertTrue(page.state.value is NavVoiceState.AmbiguousQuery)

            val frame = page.render(4) as ProjectionFrame.LiveScreen
            assertEquals("Select on phone", frame.contentTextLine1)

            // Rider can exit with BACK
            assertEquals(NavigationAction.Pop, page.onInput(FunctionId.BACK))
            workJob.cancelAndJoin()
        }
    }

    @Test
    fun `stale instruction present before launch is ignored until new instruction arrives`() = runTest {
        val staleInstruction = NavInstruction(
            title = "Old",
            streetName = "Old",
            distanceFormatted = "0m",
            maneuver = ManeuverType.STRAIGHT
        )
        instructionFlow.value = staleInstruction

        val page = provider()
        var actionResult: NavigationAction? = null
        val workJob = launch { actionResult = page.work() }
        runCurrent()

        recognizer.release("Gran Vía")
        runCurrent()

        page.onInput(FunctionId.SELECT)
        runCurrent()

        // Still launching, not replaced because staleInstruction == baseline
        assertTrue(page.state.value is NavVoiceState.Launching)
        assertEquals(null, actionResult)

        // New instruction arrives
        val newInstruction = NavInstruction(
            title = "New",
            streetName = "New",
            distanceFormatted = "500m",
            maneuver = ManeuverType.TURN_LEFT
        )
        instructionFlow.value = newInstruction
        runCurrent()

        assertTrue(actionResult is NavigationAction.Replace)
        workJob.cancelAndJoin()
    }

    @Test
    fun `speech flow emits empty list when leaving confirming face`() = runTest {
        val page = provider()
        val workJob = launch { page.work() }
        runCurrent()

        page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem())

            recognizer.release("Castellana")
            runCurrent()

            val confirmingSpeech = awaitItem()
            assertEquals(1, confirmingSpeech.size)
            assertEquals(SpeechToken.NAV_CONFIRM, confirmingSpeech[0].token)

            // Rider retries by pressing BACK -> Listening
            page.onInput(FunctionId.BACK)
            runCurrent()

            val cancelledSpeech = awaitItem()
            assertEquals(emptyList<SpeechRequest>(), cancelledSpeech)

            workJob.cancelAndJoin()
        }
    }

    @Test
    fun `mirror actions cover volume, menu, and moving keys without collisions`() {
        val page = provider()
        val mirror = page.mirror()

        assertEquals("NavVoiceSearchPage", mirror.pageName)
        val tokens = mirror.actions.map { it.token }
        assertTrue(tokens.contains(MirrorActionToken.CANCEL_DICTATION))
        assertTrue(tokens.contains(MirrorActionToken.VOLUME_UP))
        assertTrue(tokens.contains(MirrorActionToken.VOLUME_DOWN))
        assertTrue(tokens.contains(MirrorActionToken.VOLUME_MUTE))
        assertTrue(tokens.contains(MirrorActionToken.OPEN_ROOT_MENU))
    }

    @Test
    fun `no app available transitions to NoAppAvailable with failure cue and pop on back`() = runTest {
        launcher.succeeds = false
        val page = provider()
        val workJob = launch { page.work() }
        runCurrent()

        recognizer.release("Madrid")
        runCurrent()

        page.cues.test {
            page.onInput(FunctionId.SELECT)
            runCurrent()

            assertEquals(Cue.FAILURE, awaitItem())
            assertTrue(page.state.value is NavVoiceState.NoAppAvailable)

            val frame = page.render(5) as ProjectionFrame.LiveScreen
            assertEquals("No navigation app", frame.contentTextLine1)
            assertEquals(IconId.NAVIGATION, frame.contentIconId)

            assertEquals(NavigationAction.Pop, page.onInput(FunctionId.BACK))
            workJob.cancelAndJoin()
        }
    }

    @Test
    fun `speech recognition exception transitions to Failed with failure cue`() = runTest {
        recognizer.listensUntilReleased = false
        recognizer.willFail(RecognitionFailure.NO_PERMISSION)
        val page = provider()

        page.cues.test {
            val workJob = launch { page.work() }
            runCurrent()

            assertEquals(Cue.LISTEN, awaitItem())
            assertEquals(Cue.FAILURE, awaitItem())
            assertTrue(page.state.value is NavVoiceState.Failed)

            val frame = page.render(6) as ProjectionFrame.LiveScreen
            assertEquals("Microphone denied", frame.contentTextLine1)

            // BACK pops cleanly
            assertEquals(NavigationAction.Pop, page.onInput(FunctionId.BACK))
            workJob.cancelAndJoin()
        }
    }

    @Test
    fun `retry from error state on SELECT returns to Listening`() = runTest {
        val page = provider()
        val workJob = launch { page.work() }
        runCurrent()

        recognizer.release(null)
        runCurrent()
        assertEquals(NavVoiceState.NothingUnderstood, page.state.value)

        page.cues.test {
            val action = page.onInput(FunctionId.SELECT)
            assertEquals(NavigationAction.None, action)
            runCurrent()

            assertEquals(NavVoiceState.Listening, page.state.value)
            assertEquals(Cue.LISTEN, awaitItem())
            workJob.cancelAndJoin()
        }
    }

    @Test
    fun `volume keys interact with volume source`() {
        val volume = RecordingVolumeSource()
        val page = NavVoiceSearchPageProvider(
            recognizer = recognizer,
            launcher = launcher,
            unlockSource = unlocks,
            navInstructionFlow = instructionFlow,
            livePageFactory = { livePage },
            copy = copy,
            volumeSource = volume
        )

        page.onInput(FunctionId.VOLUME_UP)
        assertEquals(1, volume.raiseCalls)

        page.onInput(FunctionId.VOLUME_DOWN)
        assertEquals(1, volume.lowerCalls)

        page.onInput(FunctionId.VOLUME_MUTE)
        assertEquals(1, volume.muteCalls)

        page.onInput(FunctionId.PREVIOUS)
        assertEquals(2, volume.raiseCalls)

        page.onInput(FunctionId.NEXT)
        assertEquals(2, volume.lowerCalls)
    }

    @Test
    fun `menu key resets navigation`() {
        val page = provider()
        assertEquals(NavigationAction.Reset, page.onInput(FunctionId.MENU))
    }

    @Test
    fun `speech emits NAV_NOTHING_UNDERSTOOD on NothingUnderstood`() = runTest {
        val page = provider()
        val workJob = launch { page.work() }
        runCurrent()

        page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // Listening
            recognizer.release(null)
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.NAV_NOTHING_UNDERSTOOD)), awaitItem())
            workJob.cancelAndJoin()
        }
    }

    @Test
    fun `speech emits NAV_FAILED on Failed, whose help offers SELECT to listen again`() = runTest {
        recognizer.listensUntilReleased = false
        recognizer.willFail(RecognitionFailure.SERVER)
        val page = provider()
        page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // Listening
            val workJob = launch { page.work() }
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.NAV_FAILED)), awaitItem())
            workJob.cancelAndJoin()
        }
    }

    @Test
    fun `speech tells the rider to open the app when the microphone needs it`() = runTest {
        recognizer.listensUntilReleased = false
        recognizer.willFail(RecognitionFailure.MIC_NEEDS_APP)
        val page = provider()
        page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // Listening
            val workJob = launch { page.work() }
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.VOICE_MIC_NEEDS_APP)), awaitItem())
            workJob.cancelAndJoin()
        }
    }

    @Test
    fun `speech emits NAV_NO_APP_AVAILABLE on NoAppAvailable`() = runTest {
        launcher.succeeds = false
        val page = provider()
        val workJob = launch { page.work() }
        runCurrent()
        recognizer.release("Madrid")
        runCurrent()

        page.speech.test {
            assertEquals(listOf(SpeechRequest(SpeechToken.NAV_CONFIRM, SpeechArg.Text("Madrid"))), awaitItem())
            page.onInput(FunctionId.SELECT) // launches app
            runCurrent()
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // Launching
            assertEquals(listOf(SpeechRequest(SpeechToken.NAV_NO_APP_AVAILABLE)), awaitItem())
            workJob.cancelAndJoin()
        }
    }

    @Test
    fun `speech emits NAV_AMBIGUOUS_QUERY on AmbiguousQuery`() = runTest {
        val page = NavVoiceSearchPageProvider(
            recognizer = recognizer,
            launcher = launcher,
            unlockSource = unlocks,
            navInstructionFlow = instructionFlow,
            livePageFactory = { livePage },
            copy = copy,
            tbtWatchdogTimeout = 100.milliseconds
        )
        val workJob = launch { page.work() }
        runCurrent()
        recognizer.release("Unknown Place")
        runCurrent()

        page.speech.test {
            assertEquals(listOf(SpeechRequest(SpeechToken.NAV_CONFIRM, SpeechArg.Text("Unknown Place"))), awaitItem())
            page.onInput(FunctionId.SELECT) // launches app and starts watchdog
            runCurrent()
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // Launching
            advanceTimeBy(101.milliseconds)
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.NAV_AMBIGUOUS_QUERY)), awaitItem())
            workJob.cancelAndJoin()
        }
    }

    private fun TestScope.launchedPage(
        onAction: (NavigationAction) -> Unit = {}
    ): Pair<NavVoiceSearchPageProvider, Job> {
        val page = provider()
        val workJob = launch { onAction(page.work()) }
        runCurrent()
        recognizer.release("Santiago de Compostela")
        runCurrent()
        page.onInput(FunctionId.SELECT)
        runCurrent()
        return page to workJob
    }

    private fun newInstruction(title: String) = NavInstruction(
        title = title,
        streetName = title,
        distanceFormatted = "",
        maneuver = ManeuverType.DEPART
    )

    @Test
    fun `an unlock while waiting for guidance re-sends the route`() = runTest {
        val (_, workJob) = launchedPage()

        unlocks.unlock()
        runCurrent()

        assertEquals(listOf("Santiago de Compostela", "Santiago de Compostela"), launcher.launchedQueries.map { it.first })
        workJob.cancelAndJoin()
    }

    @Test
    fun `guidance that arrives after the watchdog still replaces with the live page`() = runTest {
        var action: NavigationAction? = null
        val (page, workJob) = launchedPage { action = it }
        advanceTimeBy(16.seconds)
        runCurrent()
        assertTrue(page.state.value is NavVoiceState.AmbiguousQuery)

        instructionFlow.value = newInstruction("Head south")
        runCurrent()

        assertEquals(NavigationAction.Replace(livePage), action)
        workJob.cancelAndJoin()
    }

    @Test
    fun `the instruction held before the launch never replaces, not even after the watchdog`() = runTest {
        instructionFlow.value = newInstruction("Old route")
        var action: NavigationAction? = null
        val (_, workJob) = launchedPage { action = it }

        advanceTimeBy(16.seconds)
        runCurrent()

        assertEquals(null, action)
        workJob.cancelAndJoin()
    }

    @Test
    fun `after BACK an unlock re-sends nothing`() = runTest {
        val (page, workJob) = launchedPage()
        advanceTimeBy(16.seconds)
        runCurrent()

        assertEquals(NavigationAction.Pop, page.onInput(FunctionId.BACK))
        workJob.cancelAndJoin() // what the engine does to the work of a page it pops
        unlocks.unlock()
        runCurrent()

        assertEquals(1, launcher.launchCalls)
        assertEquals(0, unlocks.collectors)
    }

    @Test
    fun `with the phone locked at launch the watchdog asks to unlock it`() = runTest {
        launcher.deviceLocked = true
        val (page, workJob) = launchedPage()

        page.speech.test {
            advanceTimeBy(16.seconds)
            runCurrent()

            assertEquals(NavVoiceState.UnlockPhone("Santiago de Compostela"), page.state.value)
            assertEquals(listOf(SpeechRequest(SpeechToken.NAV_UNLOCK_PHONE)), expectMostRecentItem())
        }
        val frame = page.render(4) as ProjectionFrame.LiveScreen
        assertEquals("Unlock your phone", frame.contentTextLine1)
        assertEquals(IconId.LIVE_CANCEL_ONE_BUTTON, frame.leftActionContentIconId)
        workJob.cancelAndJoin()
    }

    @Test
    fun `with the phone unlocked at launch the watchdog still says the query is ambiguous`() = runTest {
        launcher.deviceLocked = false
        val (page, workJob) = launchedPage()

        advanceTimeBy(16.seconds)
        runCurrent()

        assertEquals(NavVoiceState.AmbiguousQuery("Santiago de Compostela"), page.state.value)
        workJob.cancelAndJoin()
    }

    @Test
    fun `the unlock face leaves with BACK or SELECT, and its mirror says so`() = runTest {
        launcher.deviceLocked = true
        val (page, workJob) = launchedPage()
        advanceTimeBy(16.seconds)
        runCurrent()

        assertEquals(listOf(MirrorActionToken.GO_BACK), page.mirror().actions.filter { it.slot == MirrorSlot.LEFT }.map { it.token })
        assertEquals(NavigationAction.Pop, page.onInput(FunctionId.SELECT))
        assertEquals(NavigationAction.Pop, page.onInput(FunctionId.BACK))
        workJob.cancelAndJoin()
    }

    @Test
    fun `unlocking from the unlock face re-sends the route and replaces with the live page`() = runTest {
        launcher.deviceLocked = true
        var action: NavigationAction? = null
        val (_, workJob) = launchedPage { action = it }
        advanceTimeBy(16.seconds)
        runCurrent()

        unlocks.unlock()
        runCurrent()
        instructionFlow.value = newInstruction("Head south")
        runCurrent()

        assertEquals(2, launcher.launchCalls)
        assertEquals(NavigationAction.Replace(livePage), action)
        workJob.cancelAndJoin()
    }
}
