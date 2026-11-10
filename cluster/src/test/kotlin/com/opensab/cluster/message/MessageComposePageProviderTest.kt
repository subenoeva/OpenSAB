package com.opensab.cluster.message
import app.cash.turbine.test
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.audio.Cue
import com.opensab.domain.contacts.CallTarget
import com.opensab.domain.contacts.PhoneKind
import com.opensab.domain.message.MockSmsSender
import com.opensab.domain.music.RecordingVolumeSource
import com.opensab.domain.speech.MockSpeechRecognizer
import com.opensab.domain.speech.RecognitionFailure
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MessageComposePageProviderTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private class Harness(
        val target: CallTarget = CallTarget("Alice", "+34600123456", PhoneKind.MOBILE),
        linger: Duration = 2.seconds,
        quick: List<String> = QUICK_REPLIES,
        copy: ClusterTextResolver = englishClusterText
    ) {
        val recognizer = MockSpeechRecognizer()
        val smsSender = MockSmsSender()
        val volume = RecordingVolumeSource()
        val sentLinger = MutableStateFlow(linger)
        val quickReplies = MutableStateFlow(quick)

        val page = MessageComposePageProvider(
            target = target,
            recognizer = recognizer,
            smsSender = smsSender,
            sentLinger = sentLinger,
            copy = copy,
            quickReplies = quickReplies,
            volumeSource = volume
        )

        var outcome: NavigationAction? = null
            private set

        private var job: Job? = null

        fun mount(scope: CoroutineScope) {
            job = scope.launch { outcome = page.work() }
        }

        fun unmount() = job?.cancel()

        val liveScreen: ProjectionFrame.LiveScreen
            get() = page.render(7) as ProjectionFrame.LiveScreen

        val line: String?
            get() = liveScreen.contentTextLine1

        val menuScreen: ProjectionFrame.MenuScreen
            get() = page.render(7) as ProjectionFrame.MenuScreen
    }

    // -------------------------------------------------------------------------------------------
    // Listening
    // -------------------------------------------------------------------------------------------

    @Test
    fun `listens when it mounts and emits LISTEN cue`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        val cues = mutableListOf<Cue>()
        backgroundScope.launch { h.page.cues.collect { cues += it } }

        h.mount(backgroundScope)
        runCurrent()

        assertEquals(1, h.recognizer.listenCalls)
        assertEquals(listOf(Cue.LISTEN), cues)
    }

    @Test
    fun `shows the microphone while listening`() {
        val h = Harness()

        assertEquals(IconId.MIC, h.liveScreen.contentIconId)
        assertEquals("Say your message", h.line)
        assertEquals("icon and second line are mutually exclusive", "", h.liveScreen.contentTextLine2)
        assertEquals(ColorId.BLUE, h.liveScreen.headerBgColorId)
        assertEquals("Messages", h.liveScreen.headerText)
        assertEquals(IconId.LIVE_CANCEL_ONE_BUTTON, h.liveScreen.leftActionContentIconId)
        assertEquals(IconId.LIVE_REPLY, h.liveScreen.rightActionContentIconId)
    }

    @Test
    fun `cancels on back while listening and pops`() {
        val h = Harness()

        assertEquals(NavigationAction.Pop, h.page.onInput(FunctionId.BACK))
    }

    @Test
    fun `cancels the listen when it stops being mounted`() = testScope.runTest {
        val h = Harness()
        h.recognizer.listensUntilReleased = true
        h.mount(backgroundScope)
        runCurrent()

        h.unmount()
        runCurrent()
        h.recognizer.release("too late")
        runCurrent()

        assertEquals("Say your message", h.line)
        assertNull(h.outcome)
    }

    // -------------------------------------------------------------------------------------------
    // Confirming & TTS Review
    // -------------------------------------------------------------------------------------------

    @Test
    fun `moves to confirmation with what it heard`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")

        h.mount(backgroundScope)
        runCurrent()

        assertEquals("On my way", h.line)
        assertEquals(IconId.REVIEW, h.liveScreen.contentIconId)
        assertEquals("", h.liveScreen.contentTextLine2)
        assertEquals(IconId.LIVE_SEND, h.liveScreen.rightActionContentIconId)
        assertEquals(ColorId.POSITIVE, h.liveScreen.rightActionBgColorId)
        assertEquals(IconId.LIVE_RETRY, h.liveScreen.leftActionContentIconId)
    }

    @Test
    fun `reads transcript back via TTS`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        val spoken = mutableListOf<List<SpeechRequest>>()
        backgroundScope.launch { h.page.speech.collect { spoken += it } }

        h.mount(backgroundScope)
        runCurrent()

        assertEquals(
            listOf(listOf(SpeechRequest(SpeechToken.MESSAGE_CONFIRM, SpeechArg.Text("On my way")))),
            spoken.filter { it.isNotEmpty() }
        )
    }

    @Test
    fun `truncates content text line 1 to 30 bytes on confirmation without breaking UTF-8`() = testScope.runTest {
        val h = Harness()
        val longText = "This is an extremely long dictated message that definitely exceeds thirty bytes limit"
        h.recognizer.willHear(longText)

        h.mount(backgroundScope)
        runCurrent()

        val renderedLine = h.line ?: ""
        assertTrue("rendered line must not exceed 30 bytes", renderedLine.toByteArray(Charsets.UTF_8).size <= 30)
        assertEquals("", h.liveScreen.contentTextLine2)
    }

    @Test
    fun `listens again on retry from confirmation`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.BACK)
        runCurrent()

        assertEquals(2, h.recognizer.listenCalls)
    }

    // -------------------------------------------------------------------------------------------
    // Silence & Recognition Failures
    // -------------------------------------------------------------------------------------------

    @Test
    fun `treats silence as nothing understood and emits failure cue`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHearNothing()
        val cues = mutableListOf<Cue>()
        backgroundScope.launch { h.page.cues.collect { cues += it } }

        h.mount(backgroundScope)
        runCurrent()

        assertEquals("I didn't catch that", h.line)
        assertEquals(IconId.MIC, h.liveScreen.contentIconId)
        assertEquals(IconId.LIVE_RETRY, h.liveScreen.leftActionContentIconId)
        assertEquals(listOf(Cue.LISTEN, Cue.FAILURE), cues)
    }

    @Test
    fun `treats empty or blank transcript as nothing understood`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("   ")
        h.mount(backgroundScope)
        runCurrent()

        assertEquals("I didn't catch that", h.line)
    }

    @Test
    fun `reports voice recognition failure reasons and emits failure cue`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willFail(RecognitionFailure.NO_PERMISSION)
        val cues = mutableListOf<Cue>()
        backgroundScope.launch { h.page.cues.collect { cues += it } }

        h.mount(backgroundScope)
        runCurrent()

        assertEquals("Microphone denied", h.line)
        assertEquals(IconId.MIC, h.liveScreen.contentIconId)
        assertEquals(IconId.LIVE_RETRY, h.liveScreen.leftActionContentIconId)
        assertEquals(listOf(Cue.LISTEN, Cue.FAILURE), cues)
    }

    @Test
    fun `listens again after failure on retry press`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willFail(RecognitionFailure.NETWORK)
        h.mount(backgroundScope)
        runCurrent()

        h.recognizer.willHear("Hello")
        h.page.onInput(FunctionId.BACK)
        runCurrent()

        assertEquals(2, h.recognizer.listenCalls)
        assertEquals("Hello", h.line)
    }

    // -------------------------------------------------------------------------------------------
    // Quick Replies (Choosing)
    // -------------------------------------------------------------------------------------------

    @Test
    fun `opens quick replies on select while listening`() = testScope.runTest {
        val h = Harness()
        h.recognizer.listensUntilReleased = true
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(QUICK_REPLIES.first(), h.menuScreen.contentTextLine1)
        assertEquals(ColorId.BLUE, h.menuScreen.headerBgColorId)
        assertEquals("Messages", h.menuScreen.headerText)
    }

    @Test
    fun `select does not open quick replies when quickReplies is empty`() = testScope.runTest {
        val h = Harness(quick = emptyList())
        h.recognizer.listensUntilReleased = true
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(IconId.MIC, h.liveScreen.contentIconId)
        assertNull(h.liveScreen.rightActionContentIconId)
    }

    @Test
    fun `navigates quick replies with next and previous emitting move and edge cues`() = testScope.runTest {
        val h = Harness()
        h.recognizer.listensUntilReleased = true
        h.mount(backgroundScope)
        runCurrent()
        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        val cues = mutableListOf<Cue>()
        backgroundScope.launch { h.page.cues.collect { cues += it } }

        // At index 0, previous is edge
        h.page.onInput(FunctionId.PREVIOUS)
        runCurrent()
        assertEquals(listOf(Cue.EDGE), cues)
        assertEquals(QUICK_REPLIES[0], h.menuScreen.contentTextLine1)

        // Next moves to 1
        h.page.onInput(FunctionId.NEXT)
        runCurrent()
        assertEquals(listOf(Cue.EDGE, Cue.MOVE), cues)
        assertEquals(QUICK_REPLIES[1], h.menuScreen.contentTextLine1)

        // Previous moves back to 0
        h.page.onInput(FunctionId.PREVIOUS)
        runCurrent()
        assertEquals(listOf(Cue.EDGE, Cue.MOVE, Cue.MOVE), cues)
        assertEquals(QUICK_REPLIES[0], h.menuScreen.contentTextLine1)
    }

    @Test
    fun `volume keys act as cursor aliases on quick replies without modifying volume`() = testScope.runTest {
        val h = Harness()
        h.recognizer.listensUntilReleased = true
        h.mount(backgroundScope)
        runCurrent()
        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        h.page.onInput(FunctionId.VOLUME_DOWN)
        runCurrent()
        assertEquals(QUICK_REPLIES[1], h.menuScreen.contentTextLine1)
        assertEquals("volume must not be modified when navigating quick list", 0, h.volume.totalCalls)

        h.page.onInput(FunctionId.VOLUME_UP)
        runCurrent()
        assertEquals(QUICK_REPLIES[0], h.menuScreen.contentTextLine1)
        assertEquals(0, h.volume.totalCalls)
    }

    @Test
    fun `back on choosing returns to the face it was opened from`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHearNothing()
        h.mount(backgroundScope)
        runCurrent()
        assertEquals("I didn't catch that", h.line)

        h.page.onInput(FunctionId.SELECT)
        runCurrent()
        assertEquals(QUICK_REPLIES.first(), h.menuScreen.contentTextLine1)

        h.page.onInput(FunctionId.BACK)
        runCurrent()
        assertEquals("I didn't catch that", h.line)
    }

    @Test
    fun `selecting a quick reply transitions to sending`() = testScope.runTest {
        val h = Harness()
        h.recognizer.listensUntilReleased = true
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT) // opens quick replies
        runCurrent()

        h.page.onInput(FunctionId.NEXT) // index 1: "No"
        runCurrent()

        h.page.onInput(FunctionId.SELECT) // sends "No"
        runCurrent()

        assertEquals(listOf("+34600123456" to "No"), h.smsSender.sentMessages)
    }

    // -------------------------------------------------------------------------------------------
    // Sending, Sent, NotSent & Outcomes
    // -------------------------------------------------------------------------------------------

    @Test
    fun `sends confirmed text to target number and displays sending face`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("See you soon")
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(listOf("+34600123456" to "See you soon"), h.smsSender.sentMessages)
    }

    @Test
    fun `successful send emits SUCCESS cue, shows Sent, lingers and returns Reset`() = testScope.runTest {
        val h = Harness(linger = 3.seconds)
        h.recognizer.willHear("On my way")
        val cues = mutableListOf<Cue>()
        backgroundScope.launch { h.page.cues.collect { cues += it } }

        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(IconId.SEND, h.liveScreen.contentIconId)
        assertEquals("Sent", h.line)
        assertTrue(cues.contains(Cue.SUCCESS))
        assertNull("must not complete before linger duration", h.outcome)

        advanceTimeBy(2.seconds)
        runCurrent()
        assertNull("two seconds is less than three", h.outcome)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals("successful message compose resets to carousel root", NavigationAction.Reset, h.outcome)
    }

    @Test
    fun `delivery failure emits FAILURE cue, shows DFU_WARNING and REPLY_NOT_SENT, retries on SELECT`() = testScope.runTest {
        val h = Harness()
        h.smsSender.nextResult = false
        h.recognizer.willHear("Can't talk")
        val cues = mutableListOf<Cue>()
        backgroundScope.launch { h.page.cues.collect { cues += it } }

        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(IconId.DFU_WARNING, h.liveScreen.contentIconId)
        assertEquals("Not sent", h.line)
        assertEquals(IconId.LIVE_RETRY, h.liveScreen.leftActionContentIconId)
        assertEquals(IconId.LIVE_SEND, h.liveScreen.rightActionContentIconId)
        assertTrue(cues.contains(Cue.FAILURE))
        assertNull(h.outcome)

        // Retry via SELECT
        h.smsSender.nextResult = true
        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(2, h.smsSender.sendCount)
        assertEquals(IconId.SEND, h.liveScreen.contentIconId)
        assertEquals("Sent", h.line)
    }

    @Test
    fun `retry dictation on BACK from NotSent`() = testScope.runTest {
        val h = Harness()
        h.smsSender.nextResult = false
        h.recognizer.willHear("Faulty message")
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()
        assertEquals("Not sent", h.line)

        h.recognizer.willHear("Corrected message")
        h.page.onInput(FunctionId.BACK)
        runCurrent()

        assertEquals("Corrected message", h.line)
        assertEquals(IconId.REVIEW, h.liveScreen.contentIconId)
    }

    // -------------------------------------------------------------------------------------------
    // Navigation & Keys
    // -------------------------------------------------------------------------------------------

    @Test
    fun `resets to carousel root on MENU from every state`() = testScope.runTest {
        forEachFace { h, face ->
            assertEquals("$face must reset to carousel root on MENU", NavigationAction.Reset, h.page.onInput(FunctionId.MENU))
        }
    }

    @Test
    fun `owns volume in all states except Choosing`() = testScope.runTest {
        forEachFace { h, face ->
            if (face != "Choosing") {
                h.page.onInput(FunctionId.PREVIOUS)
                h.page.onInput(FunctionId.VOLUME_UP)
                h.page.onInput(FunctionId.NEXT)
                h.page.onInput(FunctionId.VOLUME_DOWN)
                h.page.onInput(FunctionId.VOLUME_MUTE)

                assertEquals("$face raises volume", 2, h.volume.raiseCalls)
                assertEquals("$face lowers volume", 2, h.volume.lowerCalls)
                assertEquals("$face toggles mute", 1, h.volume.muteCalls)
            }
        }
    }

    @Test
    fun `declares DISCARD resume behavior`() {
        assertEquals(ResumeBehavior.DISCARD, Harness().page.onResumeBehavior)
    }

    @Test
    fun `redraws on state transitions`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        var redraws = 0
        backgroundScope.launch { h.page.invalidations.collect { redraws++ } }

        h.mount(backgroundScope)
        runCurrent()
        assertEquals("listening to confirming", 1, redraws)

        h.page.onInput(FunctionId.SELECT)
        runCurrent()
        assertEquals("confirming to sending to sent", 3, redraws)
    }

    // -------------------------------------------------------------------------------------------
    // Mirror Synchronization
    // -------------------------------------------------------------------------------------------

    @Test
    fun `mirrors actions and lines consistently across all faces`() = testScope.runTest {
        val expected = mapOf(
            "Listening" to setOf(MirrorActionToken.CANCEL_DICTATION, MirrorActionToken.QUICK_REPLIES),
            "Confirming" to setOf(MirrorActionToken.RETRY_DICTATION, MirrorActionToken.SEND_MESSAGE),
            "NothingUnderstood" to setOf(MirrorActionToken.RETRY_DICTATION, MirrorActionToken.QUICK_REPLIES),
            "Failed" to setOf(MirrorActionToken.RETRY_DICTATION, MirrorActionToken.QUICK_REPLIES),
            "Choosing" to setOf(MirrorActionToken.GO_BACK, MirrorActionToken.SEND_MESSAGE),
            "Sending" to emptySet(),
            "Sent" to emptySet(),
            "NotSent" to setOf(MirrorActionToken.RETRY_DICTATION, MirrorActionToken.SEND_MESSAGE)
        )

        forEachFace { h, face ->
            val actions = h.page.mirror().actions
            val moving = actions
                .filter { it.slot == MirrorSlot.LEFT || it.slot == MirrorSlot.PRIMARY }
                .map { it.token }
                .toSet()

            assertEquals(face, expected.getValue(face), moving)
            assertEquals(
                "$face must retain escape to root menu",
                setOf(MirrorActionToken.OPEN_ROOT_MENU),
                actions.filter { it.slot == MirrorSlot.HEADER }.map { it.token }.toSet()
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private fun TestScope.forEachFace(check: (Harness, String) -> Unit) {
        val faces = listOf<Pair<String, (Harness) -> Unit>>(
            "Listening" to { h -> h.recognizer.listensUntilReleased = true },
            "Confirming" to { h -> h.recognizer.willHear("On my way") },
            "NothingUnderstood" to { h -> h.recognizer.willHearNothing() },
            "Failed" to { h -> h.recognizer.willFail(RecognitionFailure.NO_PERMISSION) },
            "Choosing" to { h ->
                h.recognizer.listensUntilReleased = true
            },
            "Sending" to { h ->
                h.recognizer.willHear("On my way")
            },
            "Sent" to { h ->
                h.recognizer.willHear("On my way")
            },
            "NotSent" to { h ->
                h.recognizer.willHear("On my way")
                h.smsSender.nextResult = false
            }
        )

        for ((face, setUp) in faces) {
            val h = Harness()
            setUp(h)
            h.mount(backgroundScope)
            runCurrent()
            if (face == "Choosing") {
                h.page.onInput(FunctionId.SELECT)
                runCurrent()
            } else if (face == "Sending" || face == "Sent" || face == "NotSent") {
                h.page.onInput(FunctionId.SELECT)
                runCurrent()
            }
            check(h, face)
        }
    }

    @Test
    fun `BACK from Confirming to Listening emits empty list in speech`() = runTest {
        val h = Harness()
        h.recognizer.willHear("Hello")
        h.mount(backgroundScope)
        runCurrent()
        assertEquals(MessageComposeState.Confirming("Hello"), h.page.state.value)

        h.page.speech.test {
            assertEquals(listOf(SpeechRequest(SpeechToken.MESSAGE_CONFIRM, SpeechArg.Text("Hello"))), awaitItem())
            h.page.onInput(FunctionId.BACK)
            runCurrent()
            assertEquals(emptyList<SpeechRequest>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `speech emits VOICE_NOTHING_UNDERSTOOD on NothingUnderstood`() = runTest {
        val h = Harness()
        h.recognizer.willHearNothing()
        h.page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // from initial Listening
            h.mount(backgroundScope)
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.VOICE_NOTHING_UNDERSTOOD)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `speech emits VOICE_FAILED on Failed`() = runTest {
        val h = Harness()
        h.recognizer.willFail(RecognitionFailure.SERVER)
        h.page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // from initial Listening
            h.mount(backgroundScope)
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.VOICE_FAILED)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `speech tells the rider to open the app when the microphone needs it`() = runTest {
        val h = Harness()
        h.recognizer.willFail(RecognitionFailure.MIC_NEEDS_APP)
        h.page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // from initial Listening
            h.mount(backgroundScope)
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.VOICE_MIC_NEEDS_APP)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `speech emits MESSAGE_CHOOSING_REPLY on Choosing`() = runTest {
        val h = Harness()
        h.page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // Listening
            h.page.onInput(FunctionId.SELECT) // opens quick list
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.MESSAGE_CHOOSING_REPLY, SpeechArg.Text(QUICK_REPLIES[0]))), awaitItem())
            h.page.onInput(FunctionId.NEXT)
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.MESSAGE_CHOOSING_REPLY, SpeechArg.Text(QUICK_REPLIES[1]))), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `speech emits MESSAGE_SENT on Sent and MESSAGE_NOT_SENT on NotSent`() = runTest {
        val h = Harness()
        h.smsSender.nextResult = false
        h.recognizer.willHear("Hello")
        h.page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // Listening
            h.mount(backgroundScope)
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.MESSAGE_CONFIRM, SpeechArg.Text("Hello"))), awaitItem())
            h.page.onInput(FunctionId.SELECT) // sends
            runCurrent()
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // Sending
            assertEquals(listOf(SpeechRequest(SpeechToken.MESSAGE_NOT_SENT)), awaitItem()) // NotSent

            // Retry with success
            h.smsSender.nextResult = true
            h.page.onInput(FunctionId.SELECT)
            runCurrent()
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // Sending
            assertEquals(listOf(SpeechRequest(SpeechToken.MESSAGE_SENT)), awaitItem()) // Sent
            cancelAndIgnoreRemainingEvents()
        }
    }

    companion object {
        val QUICK_REPLIES = listOf("Yes", "No", "On my way", "Can't talk right now")
    }
}
