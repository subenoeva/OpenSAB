package com.opensab.cluster.message
import app.cash.turbine.test
import com.opensab.cluster.CuingPage
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.WorkingPage
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.audio.Cue
import com.opensab.domain.message.MockMessageSource
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The seven faces of the one page that answers a message.
 *
 * The state machine, one test per transition, driven through the very [WorkingPage.work]
 * the engine runs — so what is asserted is what the rider gets and not what a private field holds.
 *
 * `runCurrent()` and `advanceTimeBy()` throughout, never `advanceUntilIdle()`: it does not run the
 * work of `backgroundScope` and leaves the virtual clock at zero, which are precisely the two
 * things every test here depends on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageReplyPageProviderTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private class Harness(
        linger: Duration = 2.seconds,
        quick: List<String> = QUICK_REPLIES,
        copy: ClusterTextResolver = englishClusterText
    ) {
        val recognizer = MockSpeechRecognizer()
        val source = MockMessageSource()
        val volume = RecordingVolumeSource()
        val sentLinger = MutableStateFlow(linger)
        val quickReplies = MutableStateFlow(quick)

        val page = MessageReplyPageProvider(
            recognizer = recognizer,
            messageSource = source,
            sentLinger = sentLinger,
            copy = copy,
            quickReplies = quickReplies,
            volumeSource = volume
        )

        /** What the work asked for when it ended, or null while it is still running. */
        var outcome: NavigationAction? = null
            private set

        private var job: Job? = null

        /** Mounts the page the way the engine does: one call to `work()` on the session scope. */
        fun mount(scope: CoroutineScope) {
            job = scope.launch { outcome = page.work() }
        }

        /** What the engine does to the work the instant the page stops being mounted. */
        fun unmount() = job?.cancel()

        val frame: ProjectionFrame.LiveScreen
            get() = page.render(7) as ProjectionFrame.LiveScreen

        val line: String?
            get() = frame.contentTextLine1

        /** The frame of the quick list, which is a menu and not a live screen. */
        val menu: ProjectionFrame.MenuScreen
            get() = page.render(7) as ProjectionFrame.MenuScreen
    }

    // -------------------------------------------------------------------------------------------
    // Listening
    // -------------------------------------------------------------------------------------------

    @Test
    fun `listens when it mounts`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")

        h.mount(backgroundScope)
        runCurrent()

        assertEquals(1, h.recognizer.listenCalls)
    }

    /**
     * The frame is up before the first suspension, so a recognizer that takes its time never leaves
     * the rider looking at the page he came from wondering whether the press registered (section 7).
     */
    @Test
    fun `shows the microphone while listening`() {
        val h = Harness()

        assertEquals(IconId.MIC, h.frame.contentIconId)
        assertEquals("Say your message", h.line)
        assertEquals("icon and second line are exclusive", "", h.frame.contentTextLine2)
    }

    @Test
    fun `cancels on back while listening and pops`() {
        val h = Harness()

        assertEquals(NavigationAction.Pop, h.page.onInput(FunctionId.BACK))
        assertEquals("cancelling a dictation gives the screen back", 1, h.source.yieldCalls)
    }

    // -------------------------------------------------------------------------------------------
    // Confirming
    // -------------------------------------------------------------------------------------------

    @Test
    fun `moves to confirmation with what it heard`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")

        h.mount(backgroundScope)
        runCurrent()

        assertEquals("On my way", h.line)
        assertEquals(IconId.REVIEW, h.frame.contentIconId)
        assertEquals("", h.frame.contentTextLine2)
        assertEquals(IconId.LIVE_SEND, h.frame.rightActionContentIconId)
        assertEquals(ColorId.POSITIVE, h.frame.rightActionBgColorId)
        assertEquals(IconId.LIVE_RETRY, h.frame.leftActionContentIconId)
    }

    /**
     * Read back, not merely shown: under a helmet at 90 km/h the rider is not reading a five-inch
     * screen, and what he is about to send is the one thing he has to be sure of.
     */
    @Test
    fun `reads the transcript back out loud`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        val said = mutableListOf<List<SpeechRequest>>()
        backgroundScope.launch { h.page.speech.collect { said += it } }

        h.mount(backgroundScope)
        runCurrent()

        assertEquals(
            listOf(listOf(SpeechRequest(SpeechToken.MESSAGE_CONFIRM, SpeechArg.Text("On my way")))),
            said.filter { it.isNotEmpty() }
        )
    }

    /** `@Back` on the confirmation retries, because what it paints is `LIVE_RETRY`. */
    @Test
    fun `listens again on retry`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.BACK)
        runCurrent()

        assertEquals(2, h.recognizer.listenCalls)
        assertEquals("retrying must not give the screen back", 0, h.source.yieldCalls)
    }

    // -------------------------------------------------------------------------------------------
    // Nothing understood, and real failures
    // -------------------------------------------------------------------------------------------

    /**
     * Silence is an outcome and not a failure: the rider opened his mouth and
     * nothing came out, and the page says so in those terms instead of reporting an error.
     */
    @Test
    fun `treats silence as an outcome and not a failure`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHearNothing()

        h.mount(backgroundScope)
        runCurrent()

        assertEquals("I didn't catch that", h.line)
        assertEquals(IconId.LIVE_RETRY, h.frame.leftActionContentIconId)
    }

    @Test
    fun `says why a failure happened`() = testScope.runTest {
        val denied = Harness()
        denied.recognizer.willFail(RecognitionFailure.NO_PERMISSION)
        denied.mount(backgroundScope)
        runCurrent()

        val missing = Harness()
        missing.recognizer.willFail(RecognitionFailure.NO_RECOGNIZER)
        missing.mount(backgroundScope)
        runCurrent()

        assertEquals("Microphone denied", denied.line)
        assertEquals("No voice input", missing.line)
        assertNotEquals("a denied microphone must not read as silence", "I didn't catch that", denied.line)
    }

    /** Every reason gets a line of its own, because the rider acts differently on each. */
    @Test
    fun `words every failure the recognizer can report`() = testScope.runTest {
        val lines = RecognitionFailure.entries.map { reason ->
            val h = Harness()
            h.recognizer.willFail(reason)
            h.mount(backgroundScope)
            runCurrent()
            h.line
        }

        assertEquals("no two reasons may read the same", RecognitionFailure.entries.size, lines.toSet().size)
        assertTrue("no reason may be left without a line", lines.none { it.isNullOrEmpty() })
    }

    @Test
    fun `listens again after a failure`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willFail(RecognitionFailure.NETWORK)
        h.mount(backgroundScope)
        runCurrent()

        h.recognizer.willHear("On my way")
        h.page.onInput(FunctionId.BACK)
        runCurrent()

        assertEquals(2, h.recognizer.listenCalls)
        assertEquals("On my way", h.line)
    }

    // -------------------------------------------------------------------------------------------
    // Sending, sent, not sent
    // -------------------------------------------------------------------------------------------

    @Test
    fun `sends what was confirmed`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(listOf("On my way"), h.source.replied)
    }

    /**
     * There is no path from the microphone to the other phone that does not go past the rider. A
     * recognizer that mishears is not a reason to send something he never approved.
     */
    @Test
    fun `never sends without a confirmation`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHearNothing()
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        h.page.onInput(FunctionId.MENU)
        runCurrent()

        assertEquals(emptyList<String>(), h.source.replied)
    }

    @Test
    fun `shows it is sending while the send is in flight`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        h.source.replySuspends = true
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals("Sending...", h.line)
        assertEquals(IconId.LOADING, h.frame.contentIconId)
        assertEquals("nothing to press while it is going", IconId.IC_NONE, h.frame.leftActionIconId)
        assertEquals(IconId.IC_NONE, h.frame.rightActionIconId)
    }

    @Test
    fun `says it is sent once the app took it`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals("Sent", h.line)
        assertEquals(IconId.SEND, h.frame.contentIconId)
    }

    /** Nothing says "sent" that was not: a refused send is reported, never dressed up. */
    @Test
    fun `reports a send that failed`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        h.source.replySucceeds = false
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals("Not sent", h.line)
        assertEquals(IconId.NO_NETWORK, h.frame.contentIconId)
        assertNull("a failed send must not pop the page", h.outcome)
    }

    @Test
    fun `retries a failed send with the same text`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        h.source.replySucceeds = false
        h.mount(backgroundScope)
        runCurrent()
        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        h.source.replySucceeds = true
        h.page.onInput(FunctionId.BACK)
        runCurrent()

        assertEquals(listOf("On my way", "On my way"), h.source.replied)
        assertEquals("Sent", h.line)
    }

    @Test
    fun `yields and pops once the reply is sent`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        h.mount(backgroundScope)
        runCurrent()
        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(1, h.source.yieldCalls)
        assertEquals(NavigationAction.Pop, h.outcome)
    }

    /** The seconds are the rider's, not the code's: what he set is what the cluster holds. */
    @Test
    fun `waits as long as the rider asked before yielding`() = testScope.runTest {
        val h = Harness(linger = 7.seconds)
        h.recognizer.willHear("On my way")
        h.mount(backgroundScope)
        runCurrent()
        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        advanceTimeBy(6.seconds)
        runCurrent()
        assertNull("six seconds is not seven", h.outcome)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(NavigationAction.Pop, h.outcome)
    }

    // -------------------------------------------------------------------------------------------
    // The keys every face answers
    // -------------------------------------------------------------------------------------------

    @Test
    fun `resets to the carousel on MENU from every state`() = testScope.runTest {
        forEachFace { h, face ->
            assertEquals(face, NavigationAction.Reset, h.page.onInput(FunctionId.MENU))
            assertEquals("$face must give the screen back on MENU", 1, h.source.yieldCalls)
        }
    }

    @Test
    fun `owns the volume in every state`() = testScope.runTest {
        forEachFace { h, face ->
            h.page.onInput(FunctionId.PREVIOUS)
            h.page.onInput(FunctionId.VOLUME_UP)
            h.page.onInput(FunctionId.NEXT)
            h.page.onInput(FunctionId.VOLUME_DOWN)
            h.page.onInput(FunctionId.VOLUME_MUTE)

            assertEquals("$face raises through both namings", 2, h.volume.raiseCalls)
            assertEquals("$face lowers through both namings", 2, h.volume.lowerCalls)
            assertEquals("$face mutes", 1, h.volume.muteCalls)
        }
    }

    /**
     * The mirror declares exactly the keys the face answers, which is the rule the frame obeys with
     * its buttons: no zone the rider can touch that does nothing, and none missing for something he
     * can do from the handlebar.
     *
     * The volume block and the escape to the root menu are on all seven, so what this pins is the
     * left and primary zones, which are the ones that move.
     */
    @Test
    fun `mirrors exactly the keys it answers`() = testScope.runTest {
        val expected = mapOf(
            "Listening" to setOf(
                MirrorActionToken.CANCEL_DICTATION,
                MirrorActionToken.QUICK_REPLIES
            ),
            "Confirming" to setOf(MirrorActionToken.RETRY_DICTATION, MirrorActionToken.SEND_MESSAGE),
            "NothingUnderstood" to setOf(
                MirrorActionToken.RETRY_DICTATION,
                MirrorActionToken.QUICK_REPLIES
            ),
            "Failed" to setOf(
                MirrorActionToken.RETRY_DICTATION,
                MirrorActionToken.QUICK_REPLIES
            ),
            "Sending" to emptySet(),
            "Sent" to emptySet(),
            "NotSent" to setOf(
                MirrorActionToken.RETRY_DICTATION,
                MirrorActionToken.QUICK_REPLIES
            )
        )

        forEachFace { h, face ->
            val actions = h.page.mirror().actions
            val moving = actions
                .filter { it.slot == MirrorSlot.LEFT || it.slot == MirrorSlot.PRIMARY }
                .map { it.token }
                .toSet()

            assertEquals(face, expected.getValue(face), moving)
            assertEquals(
                "$face must keep the volume block and the escape",
                setOf(
                    MirrorActionToken.VOLUME_UP,
                    MirrorActionToken.VOLUME_DOWN,
                    MirrorActionToken.VOLUME_MUTE,
                    MirrorActionToken.OPEN_ROOT_MENU
                ),
                actions.map { it.token }.toSet() - moving
            )
        }
    }

    /** Every face the mirror declares is a face whose frame it can twin. */
    @Test
    fun `mirrors the very line the cluster was given`() = testScope.runTest {
        forEachFace { h, face ->
            assertEquals(face, listOf(h.line), h.page.mirror().lines)
        }
    }

    @Test
    fun `redraws on every transition`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        var ticks = 0
        backgroundScope.launch { h.page.invalidations.collect { ticks++ } }

        h.mount(backgroundScope)
        runCurrent()
        assertEquals("listening to confirming", 1, ticks)

        h.page.onInput(FunctionId.SELECT)
        runCurrent()
        assertEquals("confirming to sending to sent", 3, ticks)
    }

    /**
     * The whole reason the microphone belongs to the engine: leaving the page cancels the
     * work, and the listen goes with it. Nothing here calls a stop.
     */
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

        assertEquals("the listen was cut, so nothing was understood", "Say your message", h.line)
        assertNull(h.outcome)
    }

    @Test
    fun `discards state on resume`() {
        assertEquals(ResumeBehavior.DISCARD, Harness().page.onResumeBehavior)
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Runs [check] once per face of the machine, on a page freshly driven into it.
     *
     * Each face gets a harness of its own because a press is not undoable: sweeping the keys of one
     * state on a shared page would move it into the next.
     */
    private fun TestScope.forEachFace(check: (Harness, String) -> Unit) {
        val faces = listOf<Pair<String, (Harness) -> Unit>>(
            "Listening" to { h -> h.recognizer.listensUntilReleased = true },
            "Confirming" to { h -> h.recognizer.willHear("On my way") },
            "NothingUnderstood" to { h -> h.recognizer.willHearNothing() },
            "Failed" to { h -> h.recognizer.willFail(RecognitionFailure.NO_PERMISSION) },
            "Sending" to { h ->
                h.recognizer.willHear("On my way")
                h.source.replySuspends = true
            },
            "Sent" to { h -> h.recognizer.willHear("On my way") },
            "NotSent" to { h ->
                h.recognizer.willHear("On my way")
                h.source.replySucceeds = false
            }
        )

        for ((face, setUp) in faces) {
            val h = Harness()
            setUp(h)
            h.mount(backgroundScope)
            runCurrent()
            if (face == "Sending" || face == "Sent" || face == "NotSent") {
                h.page.onInput(FunctionId.SELECT)
                runCurrent()
            }
            check(h, face)
        }
    }

    // -------------------------------------------------------------------------------------------
    // The fixed replies, for when talking is not an option
    // -------------------------------------------------------------------------------------------

    /**
     * The half of the fixed replies that is not about failure: the rider *can* speak and would rather not —
     * helmet on, someone beside him, or simply no. `@Select` is free while listening, so it opens
     * the list, and the listen is cancelled by the `collectLatest` of `work()` and by nothing
     * written for the purpose.
     */
    @Test
    fun `opens the quick replies while listening`() = testScope.runTest {
        val h = Harness()
        h.recognizer.listensUntilReleased = true
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(QUICK_REPLIES.first(), h.menu.contentTextLine1)
        h.recognizer.release("said after the list opened")
        runCurrent()
        assertEquals(
            "the listen must not be able to talk over the list",
            QUICK_REPLIES.first(),
            h.menu.contentTextLine1
        )
    }

    /** And the half that is about failure: the three ways there is nothing to hear. */
    @Test
    fun `offers the quick replies after a failure`() = testScope.runTest {
        val silent = Harness()
        silent.recognizer.willHearNothing()
        silent.mount(backgroundScope)
        runCurrent()
        silent.page.onInput(FunctionId.SELECT)
        runCurrent()

        val denied = Harness()
        denied.recognizer.willFail(RecognitionFailure.NO_PERMISSION)
        denied.mount(backgroundScope)
        runCurrent()
        denied.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(QUICK_REPLIES.first(), silent.menu.contentTextLine1)
        assertEquals(QUICK_REPLIES.first(), denied.menu.contentTextLine1)
    }

    @Test
    fun `offers the quick replies after a send that failed`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        h.source.replySucceeds = false
        h.mount(backgroundScope)
        runCurrent()
        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(QUICK_REPLIES.first(), h.menu.contentTextLine1)
    }

    /**
     * The cursor is the two keys that are the volume everywhere else on this page, and the frame
     * says so with its vertical arrows: that is what a `MenuScreen` is, and the precedent is
     * `RootMenuPageProvider`, which does not lend those two to the volume either (section 2.1).
     */
    @Test
    fun `moves the cursor with previous and next`() = testScope.runTest {
        val h = choosing(backgroundScope)

        h.page.onInput(FunctionId.NEXT)
        runCurrent()

        assertEquals(QUICK_REPLIES[1], h.menu.contentTextLine1)
        assertEquals("the cursor is not the volume here", 0, h.volume.totalCalls)

        h.page.onInput(FunctionId.PREVIOUS)
        runCurrent()
        assertEquals(QUICK_REPLIES[0], h.menu.contentTextLine1)
    }

    /**
     * While the list is up, the two vertical keys are the cursor **under either naming** and the
     * volume under neither.
     *
     * Every other page of this app declares `VOLUME_UP` as an alias of `PREVIOUS`, which is to say
     * it treats the two as one physical control; a face that answered only one of the two names
     * would work on one handlebar layout and not on the next. Mute is a key of its own and stays.
     */
    @Test
    fun `gives both namings of the vertical keys to the cursor while the list is up`() =
        testScope.runTest {
            val h = choosing(backgroundScope)

            h.page.onInput(FunctionId.VOLUME_DOWN)
            runCurrent()

            assertEquals("the cursor moved under the volume naming", QUICK_REPLIES[1], h.menu.contentTextLine1)
            assertEquals("and it moved nothing else", 0, h.volume.lowerCalls)

            h.page.onInput(FunctionId.VOLUME_UP)
            runCurrent()
            assertEquals(QUICK_REPLIES[0], h.menu.contentTextLine1)
            assertEquals(0, h.volume.raiseCalls)

            h.page.onInput(FunctionId.VOLUME_MUTE)
            assertEquals("mute is not a vertical key", 1, h.volume.muteCalls)
        }

    @Test
    fun `stops the cursor at both ends`() = testScope.runTest {
        val h = choosing(backgroundScope)

        h.page.onInput(FunctionId.PREVIOUS)
        runCurrent()
        assertEquals("the first entry does not wrap to the last", QUICK_REPLIES.first(), h.menu.contentTextLine1)

        repeat(QUICK_REPLIES.size + 3) { h.page.onInput(FunctionId.NEXT) }
        runCurrent()
        assertEquals("the last entry does not wrap to the first", QUICK_REPLIES.last(), h.menu.contentTextLine1)
    }

    @Test
    fun `paints the vertical arrows only where the cursor can go`() = testScope.runTest {
        val h = choosing(backgroundScope)

        assertEquals("nothing above the first entry", IconId.IC_NONE, h.menu.upActionIconId)
        assertEquals(IconId.MENU_ARROW_DOWN, h.menu.downActionIconId)
        assertEquals(ColorId.PURE_BLACK, h.menu.downActionIconTintColorId)

        repeat(QUICK_REPLIES.size) { h.page.onInput(FunctionId.NEXT) }
        runCurrent()

        assertEquals(IconId.MENU_ARROW_UP, h.menu.upActionIconId)
        assertEquals("nothing below the last entry", IconId.IC_NONE, h.menu.downActionIconId)
    }

    /** The other seven faces are live screens, and none of them carries a vertical arrow. */
    @Test
    fun `paints no vertical arrows on the seven live faces`() = testScope.runTest {
        forEachFace { h, face ->
            val frame = h.page.render(7)
            assertTrue("$face must render a live screen", frame is ProjectionFrame.LiveScreen)
        }
    }

    /**
     * Choosing does not send: entering the list puts the cursor on an entry, and it takes a press
     * on an entry already chosen to send it. One botched press must not put words on somebody
     * else's phone, which is the rule the carousel already follows.
     */
    @Test
    fun `sends the quick reply that was picked and only on a second press`() = testScope.runTest {
        val h = choosing(backgroundScope)
        assertEquals("entering the list sends nothing", emptyList<String>(), h.source.replied)

        h.page.onInput(FunctionId.NEXT)
        runCurrent()
        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(listOf(QUICK_REPLIES[1]), h.source.replied)
        assertEquals("Sent", h.line)
    }

    @Test
    fun `goes back to the face it came from`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHearNothing()
        h.mount(backgroundScope)
        runCurrent()
        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        h.page.onInput(FunctionId.BACK)
        runCurrent()

        assertEquals("I didn't catch that", h.line)
        assertEquals("backing out of the list is not a new listen", 1, h.recognizer.listenCalls)
    }

    /**
     * With nothing to offer, the key does nothing and the frame promises nothing — the two halves
     * of one rule, which is why the button and the branch read the same list.
     */
    @Test
    fun `offers nothing when the rider has no quick replies`() = testScope.runTest {
        val h = Harness(quick = emptyList())
        h.recognizer.willHearNothing()
        h.mount(backgroundScope)
        runCurrent()

        assertEquals(NavigationAction.None, h.page.onInput(FunctionId.SELECT))
        runCurrent()

        assertEquals("still on the outcome it was showing", "I didn't catch that", h.line)
        assertEquals(IconId.IC_NONE, h.frame.rightActionIconId)
        assertTrue(
            "no zone may stand for a list that is not there",
            h.page.mirror().actions.none { it.token == MirrorActionToken.QUICK_REPLIES }
        )
    }

    @Test
    fun `paints the quick list button on the four faces that offer it`() = testScope.runTest {
        forEachFace { h, face ->
            val offers = face in setOf("Listening", "NothingUnderstood", "Failed", "NotSent")
            val frame = h.page.render(7) as ProjectionFrame.LiveScreen

            if (offers) {
                assertEquals(face, IconId.LIVE_REPLY, frame.rightActionContentIconId)
                assertTrue(
                    "$face answers SELECT so it must declare it",
                    h.page.mirror().actions.any { it.token == MirrorActionToken.QUICK_REPLIES }
                )
            } else {
                assertTrue(
                    "$face must not paint a quick list button",
                    frame.rightActionContentIconId != IconId.LIVE_REPLY
                )
            }
        }
    }

    /** The list mirrors like any other page: the entry the cluster is showing, and its keys. */
    @Test
    fun `mirrors the entry under the cursor and the keys of the list`() = testScope.runTest {
        val h = choosing(backgroundScope)
        val mirror = h.page.mirror()

        assertEquals(listOf(QUICK_REPLIES.first()), mirror.lines)
        assertEquals(
            setOf(
                MirrorActionToken.GO_BACK,
                MirrorActionToken.SEND_MESSAGE,
                MirrorActionToken.CAROUSEL_PREVIOUS,
                MirrorActionToken.CAROUSEL_NEXT,
                MirrorActionToken.VOLUME_MUTE,
                MirrorActionToken.OPEN_ROOT_MENU
            ),
            mirror.actions.map { it.token }.toSet()
        )
    }

    /** Driven into the list the only way there is: an outcome, then the free `@Select`. */
    private fun TestScope.choosing(scope: CoroutineScope): Harness {
        val h = Harness()
        h.recognizer.willHearNothing()
        h.mount(scope)
        runCurrent()
        h.page.onInput(FunctionId.SELECT)
        runCurrent()
        return h
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
    fun `should cue every move of the quick list cursor`() = testScope.runTest {
        val h = choosing(backgroundScope)
        val heard = cuesOf(h.page)

        h.page.onInput(FunctionId.NEXT)
        h.page.onInput(FunctionId.PREVIOUS)
        runCurrent()

        assertEquals(listOf(Cue.MOVE, Cue.MOVE), heard)
    }

    @Test
    fun `should cue both ends of the quick list`() = testScope.runTest {
        val h = choosing(backgroundScope)
        val heard = cuesOf(h.page)

        h.page.onInput(FunctionId.PREVIOUS) // already at the top
        repeat(QUICK_REPLIES.size) { h.page.onInput(FunctionId.NEXT) } // walk to the bottom, and past it
        runCurrent()

        val moves = List(QUICK_REPLIES.size - 1) { Cue.MOVE }
        assertEquals(listOf(Cue.EDGE) + moves + listOf(Cue.EDGE), heard)
    }

    @Test
    fun `should cue a reply that went`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        val heard = cuesOf(h.page)
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        // The LISTEN of the dictation this reply came out of, and then the outcome of the send.
        assertEquals(listOf(Cue.LISTEN, Cue.SUCCESS), heard)
    }

    @Test
    fun `should cue a reply that did not go`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        h.source.replySucceeds = false
        val heard = cuesOf(h.page)
        h.mount(backgroundScope)
        runCurrent()

        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(listOf(Cue.LISTEN, Cue.FAILURE), heard)
    }

    @Test
    fun `should cue both dead ends of the dictation`() = testScope.runTest {
        val silent = Harness()
        silent.recognizer.willHearNothing()
        val silentHeard = cuesOf(silent.page)
        silent.mount(backgroundScope)
        runCurrent()
        // The turn was handed over first, which is what the LISTEN in front of both of them is.
        assertEquals(listOf(Cue.LISTEN, Cue.FAILURE), silentHeard)

        val denied = Harness()
        denied.recognizer.willFail(RecognitionFailure.NO_PERMISSION)
        val deniedHeard = cuesOf(denied.page)
        denied.mount(backgroundScope)
        runCurrent()
        assertEquals(listOf(Cue.LISTEN, Cue.FAILURE), deniedHeard)
    }

    /**
     * *"So it beeps and you record a message after the signal"*. The order is
     * the whole of it: a cue that sounds while the recognizer is already open loses the first half
     * second of every dictation.
     *
     * The microphone is opened and never released, so nothing this page would do **after** opening
     * it has happened when the assertion runs. A LISTEN in the list at that moment is therefore a
     * LISTEN that sounded first, and a page that cued after `listen()` leaves the list empty.
     */
    @Test
    fun `should cue the listen before the microphone opens`() = testScope.runTest {
        val h = Harness()
        h.recognizer.listensUntilReleased = true
        val heard = cuesOf(h.page)

        h.mount(backgroundScope)
        runCurrent()

        assertEquals("the microphone is open and stays open", 1, h.recognizer.listenCalls)
        assertEquals(listOf(Cue.LISTEN), heard)
    }

    @Test
    fun `should take the five lines of its faces from the resolver`() = testScope.runTest {
        val testCopy = ClusterTextResolver { "[${it.name}]" }
        val hListening = Harness(copy = testCopy)
        assertEquals("[REPLY_SAY_YOUR_MESSAGE]", hListening.line)
        assertEquals("[HEADER_MESSAGES]", hListening.frame.headerText)

        val hNothing = Harness(copy = testCopy)
        hNothing.recognizer.willHearNothing()
        hNothing.mount(backgroundScope)
        runCurrent()
        assertEquals("[LISTEN_NOTHING_UNDERSTOOD]", hNothing.line)
        assertEquals("[HEADER_MESSAGES]", hNothing.frame.headerText)

        val hSending = Harness(copy = testCopy)
        hSending.recognizer.willHear("On my way")
        hSending.source.replySuspends = true
        hSending.mount(backgroundScope)
        runCurrent()
        hSending.page.onInput(FunctionId.SELECT)
        runCurrent()
        assertEquals("[REPLY_SENDING]", hSending.line)
        assertEquals("[HEADER_MESSAGES]", hSending.frame.headerText)

        val hSent = Harness(copy = testCopy)
        hSent.recognizer.willHear("On my way")
        hSent.mount(backgroundScope)
        runCurrent()
        hSent.page.onInput(FunctionId.SELECT)
        runCurrent()
        assertEquals("[REPLY_SENT]", hSent.line)
        assertEquals("[HEADER_MESSAGES]", hSent.frame.headerText)

        val hNotSent = Harness(copy = testCopy)
        hNotSent.recognizer.willHear("On my way")
        hNotSent.source.replySucceeds = false
        hNotSent.mount(backgroundScope)
        runCurrent()
        hNotSent.page.onInput(FunctionId.SELECT)
        runCurrent()
        assertEquals("[REPLY_NOT_SENT]", hNotSent.line)
        assertEquals("[HEADER_MESSAGES]", hNotSent.frame.headerText)
    }

    @Test
    fun `should take the ten listen failures from the resolver`() = testScope.runTest {
        val expected = mapOf(
            RecognitionFailure.NO_PERMISSION to "[LISTEN_NO_PERMISSION]",
            RecognitionFailure.NO_RECOGNIZER to "[LISTEN_NO_RECOGNIZER]",
            RecognitionFailure.NETWORK to "[LISTEN_NETWORK]",
            RecognitionFailure.AUDIO to "[LISTEN_AUDIO]",
            RecognitionFailure.SERVER to "[LISTEN_SERVER]",
            RecognitionFailure.CLIENT to "[LISTEN_CLIENT]",
            RecognitionFailure.BUSY to "[LISTEN_BUSY]",
            RecognitionFailure.LANGUAGE to "[LISTEN_LANGUAGE]",
            RecognitionFailure.UNKNOWN to "[LISTEN_UNKNOWN]",
            RecognitionFailure.MIC_NEEDS_APP to "[LISTEN_OPEN_APP]"
        )

        for ((reason, line) in expected) {
            val h = Harness(copy = ClusterTextResolver { "[${it.name}]" })
            h.recognizer.willFail(reason)
            h.mount(backgroundScope)
            runCurrent()

            assertEquals("$reason", line, h.frame.contentTextLine1)
            assertEquals("[HEADER_MESSAGES]", h.frame.headerText)
        }
    }

    @Test
    fun `should take the header of the quick list from the resolver`() = testScope.runTest {
        val h = Harness(copy = ClusterTextResolver { "[${it.name}]" })
        h.page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals("[HEADER_MESSAGES]", h.menu.headerText)
    }

    @Test
    fun `BACK from Confirming to Listening emits empty list in speech`() = testScope.runTest {
        val h = Harness()
        h.recognizer.willHear("On my way")
        h.mount(backgroundScope)
        runCurrent()
        assertEquals(ReplyState.Confirming("On my way"), h.page.state.value)

        h.recognizer.listensUntilReleased = true
        h.page.speech.test {
            assertEquals(listOf(SpeechRequest(SpeechToken.MESSAGE_CONFIRM, SpeechArg.Text("On my way"))), awaitItem())
            h.page.onInput(FunctionId.BACK)
            runCurrent()
            assertEquals(emptyList<SpeechRequest>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `speech emits VOICE_NOTHING_UNDERSTOOD on NothingUnderstood`() = testScope.runTest {
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
    fun `speech emits VOICE_FAILED on Failed`() = testScope.runTest {
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
    fun `speech tells the rider to open the app when the microphone needs it`() = testScope.runTest {
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
    fun `speech emits MESSAGE_CHOOSING_REPLY on Choosing`() = testScope.runTest {
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
    fun `speech emits MESSAGE_SENT on Sent and REPLY_NOT_SENT on NotSent, where BACK retries the send`() = testScope.runTest {
        val h = Harness()
        h.source.replySucceeds = false
        h.recognizer.willHear("On my way")
        h.page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // Listening
            h.mount(backgroundScope)
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.MESSAGE_CONFIRM, SpeechArg.Text("On my way"))), awaitItem())
            h.page.onInput(FunctionId.SELECT) // sends
            runCurrent()
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // Sending
            assertEquals(listOf(SpeechRequest(SpeechToken.REPLY_NOT_SENT)), awaitItem()) // NotSent

            // Retry with replySucceeds = true via BACK (RETRY_DICTATION)
            h.source.replySucceeds = true
            h.page.onInput(FunctionId.BACK)
            runCurrent()
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // Sending
            assertEquals(listOf(SpeechRequest(SpeechToken.MESSAGE_SENT)), awaitItem()) // Sent
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        val QUICK_REPLIES = listOf("On my way", "Can't talk now", "OK")
    }
}
