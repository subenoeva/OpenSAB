package com.opensab.cluster.message
import app.cash.turbine.test
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.StubPageProvider
import com.opensab.cluster.call.DialSeed
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.audio.Cue
import com.opensab.domain.contacts.CallTarget
import com.opensab.domain.contacts.Contact
import com.opensab.domain.contacts.MockContactDirectory
import com.opensab.domain.contacts.PhoneKind
import com.opensab.domain.contacts.PhoneNumber
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageContactSelectPageProviderTest {

    private val recognizer = MockSpeechRecognizer()
    private val directory = MockContactDirectory()
    private val volume = RecordingVolumeSource()
    private val composePage = StubPageProvider("MessageComposePage", ProjectionFrame.RequestSabMode(1))
    private val composeTargets = mutableListOf<CallTarget>()

    private val anaMobile = CallTarget("Ana Ruiz", "600100100", PhoneKind.MOBILE)
    private val anaHome = CallTarget("Ana Ruiz", "600200200", PhoneKind.HOME)

    private fun page(
        seed: DialSeed,
        copy: ClusterTextResolver = englishClusterText
    ) = MessageContactSelectPageProvider(
        seed = seed,
        recognizer = recognizer,
        directory = directory,
        composePageFactory = { target ->
            composeTargets += target
            composePage
        },
        copy = copy,
        volumeSource = volume
    )

    @Test
    fun `should start on the confirmation when seeded with favourites`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile)))

        assertEquals(MessageContactSelectState.Confirming(listOf(anaMobile), 0), page.state.value)
        assertEquals("a seeded page has nothing to listen for", 0, recognizer.listenCalls)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `should refuse to be seeded with empty favourites`() {
        page(DialSeed.Favorites(emptyList()))
    }

    @Test
    fun `should show contact name and kind on the confirmation face with blue styling`() {
        val page = page(DialSeed.Favorites(listOf(anaMobile)))

        val frame = page.render(1) as ProjectionFrame.LiveScreen

        assertEquals("Messages", frame.headerText)
        assertEquals(ColorId.BLUE, frame.headerBgColorId)
        assertEquals(ColorId.BLUE, frame.bodyBgColorId)
        assertEquals(ColorId.BLUE_DARK, frame.contentBgColorId)
        assertEquals("Ana Ruiz", frame.contentTextLine1)
        assertEquals("Mobile", frame.contentTextLine2)
        assertEquals(IconId.IC_NONE, frame.contentIconId)
        assertEquals(IconId.LIVE_CANCEL_ONE_BUTTON, frame.leftActionContentIconId)
        assertEquals(IconId.LIVE_REPLY, frame.rightActionContentIconId)
        assertEquals(ColorId.POSITIVE, frame.rightActionBgColorId)
    }

    @Test
    fun `should replace itself with compose page when SELECT is pressed on candidate`() {
        val page = page(DialSeed.Favorites(listOf(anaMobile, anaHome)))

        val action = page.onInput(FunctionId.SELECT)

        assertEquals(NavigationAction.Replace(composePage), action)
        assertEquals(listOf(anaMobile), composeTargets)
    }

    @Test
    fun `should select the candidate under the moved cursor`() {
        val page = page(DialSeed.Favorites(listOf(anaMobile, anaHome)))

        page.onInput(FunctionId.NEXT)
        val action = page.onInput(FunctionId.SELECT)

        assertEquals(NavigationAction.Replace(composePage), action)
        assertEquals(listOf(anaHome), composeTargets)
    }

    @Test
    fun `should leave on BACK when seeded with favourites`() {
        val page = page(DialSeed.Favorites(listOf(anaMobile)))
        assertEquals(NavigationAction.Pop, page.onInput(FunctionId.BACK))
    }

    @Test
    fun `should start in Listening when seeded with voice`() {
        val page = page(DialSeed.Voice)

        assertEquals(MessageContactSelectState.Listening, page.state.value)

        val frame = page.render(1) as ProjectionFrame.LiveScreen
        assertEquals("Messages", frame.headerText)
        assertEquals(ColorId.BLUE, frame.headerBgColorId)
        assertEquals(ColorId.BLUE, frame.bodyBgColorId)
        assertEquals(ColorId.BLUE_DARK, frame.contentBgColorId)
        assertEquals("Say a name", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
        assertEquals(IconId.MIC, frame.contentIconId)
        assertEquals(IconId.LIVE_CANCEL_ONE_BUTTON, frame.leftActionContentIconId)
    }

    @Test
    fun `should pop on BACK while listening`() = runTest {
        recognizer.listensUntilReleased = true
        val page = page(DialSeed.Voice)
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(MessageContactSelectState.Listening, page.state.value)
        assertEquals(NavigationAction.Pop, page.onInput(FunctionId.BACK))
    }

    @Test
    fun `should listen and confirm ranked candidates of what it heard`() = runTest {
        recognizer.willHear("envia mensaje a Ana")
        directory.results = listOf(
            Contact(1, "Ana Ruiz", starred = false, numbers = listOf(PhoneNumber("600100100", PhoneKind.MOBILE))),
            Contact(2, "Ana Torres", starred = false, numbers = listOf(PhoneNumber("600300300", PhoneKind.WORK)))
        )
        val page = page(DialSeed.Voice)
        backgroundScope.launch { page.work() }
        runCurrent()

        val expected = listOf(
            anaMobile,
            CallTarget("Ana Torres", "600300300", PhoneKind.WORK)
        )
        assertEquals(MessageContactSelectState.Confirming(expected, 0), page.state.value)
    }

    @Test
    fun `should search address book with courtesy verb removed`() = runTest {
        recognizer.willHear("llama a Ana")
        directory.results = listOf(
            Contact(1, "Ana Ruiz", starred = false, numbers = listOf(PhoneNumber("600100100", PhoneKind.MOBILE)))
        )
        val page = page(DialSeed.Voice)
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals("ana", directory.lastQuery)
        assertEquals(MessageContactSelectState.Confirming(listOf(anaMobile), 0), page.state.value)
    }

    @Test
    fun `should treat silence as NothingUnderstood`() = runTest {
        recognizer.willHearNothing()
        val page = page(DialSeed.Voice)
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(MessageContactSelectState.NothingUnderstood, page.state.value)
        val frame = page.render(1) as ProjectionFrame.LiveScreen
        assertEquals("I didn't catch that", frame.contentTextLine1)
        assertEquals(IconId.MIC, frame.contentIconId)
        assertEquals(IconId.LIVE_RETRY, frame.leftActionContentIconId)
    }

    @Test
    fun `should transition to NoMatch when nobody matches in address book`() = runTest {
        recognizer.willHear("Pepe")
        directory.results = emptyList()
        val page = page(DialSeed.Voice)
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(MessageContactSelectState.NoMatch("Pepe"), page.state.value)
        val frame = page.render(1) as ProjectionFrame.LiveScreen
        assertEquals("Not in contacts", frame.contentTextLine1)
        assertEquals(IconId.MIC, frame.contentIconId)
        assertEquals(IconId.LIVE_RETRY, frame.leftActionContentIconId)
    }

    @Test
    fun `should transition to Failed on SpeechRecognitionException`() = runTest {
        val page = page(DialSeed.Voice)
        recognizer.willFail(RecognitionFailure.NO_PERMISSION)
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(MessageContactSelectState.Failed(RecognitionFailure.NO_PERMISSION), page.state.value)
        val frame = page.render(1) as ProjectionFrame.LiveScreen
        assertEquals("Microphone denied", frame.contentTextLine1)
        assertEquals(IconId.MIC, frame.contentIconId)
        assertEquals(IconId.LIVE_RETRY, frame.leftActionContentIconId)
    }

    @Test
    fun `should listen again from dead ends on BACK`() = runTest {
        val deadEnds = listOf<(MockSpeechRecognizer, MockContactDirectory) -> Unit>(
            { r, _ -> r.willHearNothing() },
            { r, d ->
                r.willHear("Unknown")
                d.results = emptyList()
            },
            { r, _ -> r.willFail(RecognitionFailure.NETWORK) }
        )

        for (setup in deadEnds) {
            val r = MockSpeechRecognizer()
            val d = MockContactDirectory()
            setup(r, d)
            val p = MessageContactSelectPageProvider(
                seed = DialSeed.Voice,
                recognizer = r,
                directory = d,
                composePageFactory = { composePage },
                copy = englishClusterText,
                volumeSource = volume
            )
            backgroundScope.launch { p.work() }
            runCurrent()

            assertEquals(1, r.listenCalls)

            r.willHear("Ana")
            d.results = listOf(
                Contact(1, "Ana Ruiz", starred = false, numbers = listOf(PhoneNumber("600100100", PhoneKind.MOBILE)))
            )
            val action = p.onInput(FunctionId.BACK)
            runCurrent()

            assertEquals(NavigationAction.None, action)
            assertEquals(2, r.listenCalls)
            assertEquals(MessageContactSelectState.Confirming(listOf(anaMobile), 0), p.state.value)
        }
    }

    @Test
    fun `should return to listening on BACK from confirmation when seeded with Voice`() = runTest {
        recognizer.willHear("Ana")
        directory.results = listOf(
            Contact(1, "Ana Ruiz", starred = false, numbers = listOf(PhoneNumber("600100100", PhoneKind.MOBILE)))
        )
        val page = page(DialSeed.Voice)
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(MessageContactSelectState.Confirming(listOf(anaMobile), 0), page.state.value)

        val nav = page.onInput(FunctionId.BACK)
        assertEquals(NavigationAction.None, nav)
        assertEquals(MessageContactSelectState.Listening, page.state.value)
        runCurrent()

        assertEquals(2, recognizer.listenCalls)
    }

    @Test
    fun `should read out candidate under cursor via CALL_CONFIRM`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile)))
        val spoken = mutableListOf<String>()
        backgroundScope.launch {
            page.speech.collect { requests ->
                spoken += requests.single().let { request ->
                    assertEquals(SpeechToken.CALL_CONFIRM, request.token)
                    (request.args.single() as SpeechArg.Text).value
                }
            }
        }
        runCurrent()

        assertEquals(listOf("Ana Ruiz"), spoken)
    }

    @Test
    fun `should read new candidate name every time cursor moves`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile, anaHome)))
        val spoken = mutableListOf<String>()
        backgroundScope.launch {
            page.speech.collect { requests ->
                spoken += requests.single().let { request ->
                    assertEquals(SpeechToken.CALL_CONFIRM, request.token)
                    (request.args.single() as SpeechArg.Text).value
                }
            }
        }
        runCurrent()

        page.onInput(FunctionId.NEXT)
        runCurrent()

        assertEquals(listOf("Ana Ruiz", "Ana Ruiz"), spoken)
    }

    @Test
    fun `should move cursor with vertical keys and emit MOVE or EDGE cues`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile, anaHome)))
        val cues = mutableListOf<Cue>()
        backgroundScope.launch { page.cues.collect { cues += it } }
        runCurrent()

        // Cursor at 0, moving up hits EDGE
        page.onInput(FunctionId.PREVIOUS)
        runCurrent()
        assertEquals(MessageContactSelectState.Confirming(listOf(anaMobile, anaHome), 0), page.state.value)
        assertEquals(listOf(Cue.EDGE), cues)

        // Cursor moves to 1 emits MOVE
        page.onInput(FunctionId.NEXT)
        runCurrent()
        assertEquals(MessageContactSelectState.Confirming(listOf(anaMobile, anaHome), 1), page.state.value)
        assertEquals(listOf(Cue.EDGE, Cue.MOVE), cues)

        // Cursor at 1, moving down hits EDGE
        page.onInput(FunctionId.VOLUME_DOWN)
        runCurrent()
        assertEquals(MessageContactSelectState.Confirming(listOf(anaMobile, anaHome), 1), page.state.value)
        assertEquals(listOf(Cue.EDGE, Cue.MOVE, Cue.EDGE), cues)

        // Cursor moves back to 0 with VOLUME_UP emits MOVE
        page.onInput(FunctionId.VOLUME_UP)
        runCurrent()
        assertEquals(MessageContactSelectState.Confirming(listOf(anaMobile, anaHome), 0), page.state.value)
        assertEquals(listOf(Cue.EDGE, Cue.MOVE, Cue.EDGE, Cue.MOVE), cues)
    }

    @Test
    fun `should emit Cue LISTEN before listening and FAILURE on error`() = runTest {
        val cues = mutableListOf<Cue>()
        val page = page(DialSeed.Voice)
        backgroundScope.launch { page.cues.collect { cues += it } }
        runCurrent()

        recognizer.willHearNothing()
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(listOf(Cue.LISTEN, Cue.FAILURE), cues)
    }

    @Test
    fun `should own volume on all faces except confirmation`() = runTest {
        val pages = listOf(
            page(DialSeed.Voice),
            page(DialSeed.Voice).also {
                recognizer.willHearNothing()
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            page(DialSeed.Voice).also {
                recognizer.willHear("Unknown")
                directory.results = emptyList()
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            page(DialSeed.Voice).also {
                recognizer.willFail(RecognitionFailure.NETWORK)
                backgroundScope.launch { it.work() }
                runCurrent()
            }
        )

        for (p in pages) {
            val initialRaise = volume.raiseCalls
            val initialLower = volume.lowerCalls
            val initialMute = volume.muteCalls

            p.onInput(FunctionId.PREVIOUS)
            p.onInput(FunctionId.VOLUME_UP)
            p.onInput(FunctionId.NEXT)
            p.onInput(FunctionId.VOLUME_DOWN)
            p.onInput(FunctionId.VOLUME_MUTE)

            assertEquals(initialRaise + 2, volume.raiseCalls)
            assertEquals(initialLower + 2, volume.lowerCalls)
            assertEquals(initialMute + 1, volume.muteCalls)
        }

        val pConfirming = page(DialSeed.Favorites(listOf(anaMobile, anaHome)))
        val volCallsBefore = volume.totalCalls
        pConfirming.onInput(FunctionId.PREVIOUS)
        pConfirming.onInput(FunctionId.VOLUME_UP)
        pConfirming.onInput(FunctionId.NEXT)
        pConfirming.onInput(FunctionId.VOLUME_DOWN)
        assertEquals(volCallsBefore, volume.totalCalls)

        pConfirming.onInput(FunctionId.VOLUME_MUTE)
        assertEquals(volCallsBefore + 1, volume.totalCalls)
    }

    @Test
    fun `should reset on MENU from every face`() = runTest {
        val pages = listOf(
            "Listening" to page(DialSeed.Voice),
            "Confirming" to page(DialSeed.Favorites(listOf(anaMobile))),
            "NothingUnderstood" to page(DialSeed.Voice).also {
                recognizer.willHearNothing()
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            "NoMatch" to page(DialSeed.Voice).also {
                recognizer.willHear("Unknown")
                directory.results = emptyList()
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            "Failed" to page(DialSeed.Voice).also {
                recognizer.willFail(RecognitionFailure.AUDIO)
                backgroundScope.launch { it.work() }
                runCurrent()
            }
        )

        for ((name, p) in pages) {
            assertEquals("$name resets on MENU", NavigationAction.Reset, p.onInput(FunctionId.MENU))
        }
    }

    @Test
    fun `should mirror exactly the actions it answers`() = runTest {
        val voiceConfirming = page(DialSeed.Voice).also {
            recognizer.willHear("Ana")
            directory.results = listOf(
                Contact(1, "Ana Ruiz", false, listOf(PhoneNumber("600100100", PhoneKind.MOBILE)))
            )
            backgroundScope.launch { it.work() }
            runCurrent()
        }

        val expectedMoving = mapOf(
            "Listening" to setOf(MirrorActionToken.CANCEL_DICTATION),
            "ConfirmingVoice" to setOf(MirrorActionToken.RETRY_DICTATION, MirrorActionToken.OPEN),
            "ConfirmingFav" to setOf(MirrorActionToken.GO_BACK, MirrorActionToken.OPEN),
            "NothingUnderstood" to setOf(MirrorActionToken.RETRY_DICTATION),
            "NoMatch" to setOf(MirrorActionToken.RETRY_DICTATION),
            "Failed" to setOf(MirrorActionToken.RETRY_DICTATION)
        )

        val pages = listOf(
            "Listening" to page(DialSeed.Voice),
            "ConfirmingVoice" to voiceConfirming,
            "ConfirmingFav" to page(DialSeed.Favorites(listOf(anaMobile))),
            "NothingUnderstood" to page(DialSeed.Voice).also {
                recognizer.willHearNothing()
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            "NoMatch" to page(DialSeed.Voice).also {
                recognizer.willHear("Nobody")
                directory.results = emptyList()
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            "Failed" to page(DialSeed.Voice).also {
                recognizer.willFail(RecognitionFailure.SERVER)
                backgroundScope.launch { it.work() }
                runCurrent()
            }
        )

        for ((name, p) in pages) {
            val actions = p.mirror().actions
            val moving = actions
                .filter { it.slot == MirrorSlot.LEFT || it.slot == MirrorSlot.PRIMARY }
                .map { it.token }
                .toSet()

            assertEquals(name, expectedMoving.getValue(name), moving)

            val verticalTokens = actions
                .filter { it.slot == MirrorSlot.UP || it.slot == MirrorSlot.DOWN }
                .map { it.token }
                .toSet()

            if (name.startsWith("Confirming")) {
                assertEquals(name, setOf(MirrorActionToken.CAROUSEL_PREVIOUS, MirrorActionToken.CAROUSEL_NEXT), verticalTokens)
            } else {
                assertEquals(name, setOf(MirrorActionToken.VOLUME_UP, MirrorActionToken.VOLUME_DOWN), verticalTokens)
            }

            assertTrue(actions.any { it.token == MirrorActionToken.VOLUME_MUTE && it.slot == MirrorSlot.RIGHT })
            assertTrue(actions.any { it.token == MirrorActionToken.OPEN_ROOT_MENU && it.slot == MirrorSlot.HEADER })
        }
    }

    @Test
    fun `should emit invalidation on state changes`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile, anaHome)))
        var invalidations = 0
        backgroundScope.launch {
            page.invalidations.collect { invalidations++ }
        }
        runCurrent()

        val before = invalidations
        page.onInput(FunctionId.NEXT)
        runCurrent()

        assertTrue("invalidations emitted after cursor moved", invalidations > before)
    }

    @Test
    fun `BACK from Confirming to Listening emits empty list in speech`() = runTest {
        val page = page(DialSeed.Voice)
        directory.results = listOf(Contact(1L, "Ana Ruiz", false, listOf(PhoneNumber("600100100", PhoneKind.MOBILE))))
        recognizer.willHear("Ana")
        backgroundScope.launch { page.work() }
        runCurrent()
        assertEquals(MessageContactSelectState.Confirming(listOf(anaMobile), 0), page.state.value)

        page.speech.test {
            assertEquals(listOf(SpeechRequest(SpeechToken.CALL_CONFIRM, SpeechArg.Text("Ana Ruiz"))), awaitItem())
            page.onInput(FunctionId.BACK)
            runCurrent()
            assertEquals(emptyList<SpeechRequest>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `speech emits VOICE_NOTHING_UNDERSTOOD on NothingUnderstood`() = runTest {
        val page = page(DialSeed.Voice)
        recognizer.willHearNothing()
        page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // from initial Listening
            backgroundScope.launch { page.work() }
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.VOICE_NOTHING_UNDERSTOOD)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `speech emits VOICE_FAILED on Failed`() = runTest {
        val page = page(DialSeed.Voice)
        recognizer.willFail(RecognitionFailure.SERVER)
        page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // from initial Listening
            backgroundScope.launch { page.work() }
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.VOICE_FAILED)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `speech tells the rider to open the app when the microphone needs it`() = runTest {
        val page = page(DialSeed.Voice)
        recognizer.willFail(RecognitionFailure.MIC_NEEDS_APP)
        page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // from initial Listening
            backgroundScope.launch { page.work() }
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.VOICE_MIC_NEEDS_APP)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `speech emits VOICE_NO_MATCH on NoMatch`() = runTest {
        val page = page(DialSeed.Voice)
        recognizer.willHear("Pepe")
        page.speech.test {
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // from initial Listening
            backgroundScope.launch { page.work() }
            runCurrent()
            assertEquals(listOf(SpeechRequest(SpeechToken.VOICE_NO_MATCH, SpeechArg.Text("Pepe"))), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
