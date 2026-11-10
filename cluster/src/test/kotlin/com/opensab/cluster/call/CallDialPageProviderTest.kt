package com.opensab.cluster.call
import app.cash.turbine.test
import com.opensab.cluster.CuingPage
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.StubPageProvider
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.audio.Cue
import com.opensab.domain.call.CallDialer
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.CallStatus
import com.opensab.domain.call.MockCallDialer
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
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallDialPageProviderTest {

    private val recognizer = MockSpeechRecognizer()
    private val directory = MockContactDirectory()
    private val dialer = MockCallDialer()
    private val volume = RecordingVolumeSource()
    private val livePage = StubPageProvider("CallPage", ProjectionFrame.RequestSabMode(1))
    private val activeCall = MutableStateFlow<CallInfo?>(null)

    private val anaMobile = CallTarget("Ana Ruiz", "600100100", PhoneKind.MOBILE)
    private val anaHome = CallTarget("Ana Ruiz", "600200200", PhoneKind.HOME)

    private fun page(
        seed: DialSeed,
        copy: ClusterTextResolver = englishClusterText
    ) = CallDialPageProvider(
        seed = seed,
        recognizer = recognizer,
        directory = directory,
        dialer = dialer,
        livePageFactory = { livePage },
        activeCall = activeCall,
        copy = copy,
        volumeSource = volume
    )

    @Test
    fun `should start on the confirmation when it is seeded with favourites`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile)))

        assertEquals(DialState.Confirming(listOf(anaMobile), 0), page.state.value)
        assertEquals("a seeded page has nothing to listen for", 0, recognizer.listenCalls)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `should refuse to be seeded with no favourites`() {
        page(DialSeed.Favorites(emptyList()))
    }

    @Test
    fun `should show the name and the number kind on the confirmation`() {
        val page = page(DialSeed.Favorites(listOf(anaMobile)))

        val frame = page.render(1) as ProjectionFrame.LiveScreen

        assertEquals("Ana Ruiz", frame.contentTextLine1)
        assertEquals("Mobile", frame.contentTextLine2)
        assertEquals(IconId.IC_NONE, frame.contentIconId)
    }

    @Test
    fun `should place the call of the target under the cursor`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile, anaHome)))
        backgroundScope.launch { page.work() }
        runCurrent()

        page.onInput(FunctionId.NEXT)
        page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(listOf("600200200"), dialer.placedNumbers)
    }

    @Test
    fun `should replace itself with the live call page once it is placed`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile)))
        val action = async { page.work() }
        runCurrent()

        page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(NavigationAction.Replace(livePage), action.await())
    }

    /**
     * The dialler's notification, and with it the live call, arrives about a second after the
     * intent goes out. Handing over before it painted "No calls" between "Calling..." and the call
     * on the 2026-09-30 ride.
     */
    @Test
    fun `should keep calling until the call shows up`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile)))
        val action = async { page.work() }
        runCurrent()

        page.onInput(FunctionId.SELECT)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue("no live call yet, so no hand-over", action.isActive)
        assertEquals(DialState.Placing(anaMobile), page.state.value)

        activeCall.value = CallInfo("Ana Ruiz", "600100100", CallStatus.ACTIVE)
        runCurrent()

        assertEquals(NavigationAction.Replace(livePage), action.await())
    }

    @Test
    fun `should hand over anyway when the call never shows up`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile)))
        val action = async { page.work() }
        runCurrent()

        page.onInput(FunctionId.SELECT)
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()

        assertTrue(action.isCompleted)
        assertEquals(NavigationAction.Replace(livePage), action.await())
    }

    @Test
    fun `should show a failed call instead of claiming it went`() = runTest {
        dialer.succeeds = false
        val page = page(DialSeed.Favorites(listOf(anaMobile)))
        backgroundScope.launch { page.work() }
        runCurrent()

        page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(DialState.NotPlaced(anaMobile), page.state.value)
        val frame = page.render(1) as ProjectionFrame.LiveScreen
        assertEquals("Call failed", frame.contentTextLine1)
        assertEquals(IconId.CANCEL, frame.contentIconId)
    }

    @Test
    fun `should retry the very same call from a failed one`() = runTest {
        dialer.succeeds = false
        val page = page(DialSeed.Favorites(listOf(anaMobile)))
        val action = async { page.work() }
        runCurrent()

        page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(DialState.NotPlaced(anaMobile), page.state.value)

        dialer.succeeds = true
        page.onInput(FunctionId.BACK)
        runCurrent()

        assertEquals(listOf("600100100", "600100100"), dialer.placedNumbers)
        assertEquals(NavigationAction.Replace(livePage), action.await())
    }

    @Test
    fun `should answer no key while the call is being placed`() = runTest {
        val suspendingDialer = object : CallDialer {
            val gate = CompletableDeferred<Boolean>()
            override suspend fun place(number: String): Boolean = gate.await()
        }
        val page = CallDialPageProvider(
            seed = DialSeed.Favorites(listOf(anaMobile)),
            recognizer = recognizer,
            directory = directory,
            dialer = suspendingDialer,
            livePageFactory = { livePage },
            copy = englishClusterText,
            volumeSource = volume,
            activeCall = activeCall
        )
        backgroundScope.launch { page.work() }
        runCurrent()

        page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(DialState.Placing(anaMobile), page.state.value)
        assertEquals(NavigationAction.None, page.onInput(FunctionId.BACK))
        assertEquals(NavigationAction.None, page.onInput(FunctionId.SELECT))

        suspendingDialer.gate.complete(true)
        runCurrent()
    }

    @Test
    fun `should leave on back when it was seeded with favourites`() {
        val page = page(DialSeed.Favorites(listOf(anaMobile)))
        assertEquals(NavigationAction.Pop, page.onInput(FunctionId.BACK))
    }

    @Test
    fun `should pop to the two-row menu on back while listening`() = runTest {
        // The microphone has to be really open, or the page would have fallen through to
        // NothingUnderstood and the press under test would be the dead end's, not the listen's.
        recognizer.listensUntilReleased = true
        val page = page(DialSeed.Voice)
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(DialState.Listening, page.state.value)

        // One step up the stack and not a Reset: the stack is carousel -> menu -> dialler, so
        // cancelling the dictation leaves the cluster on the call menu and getting out altogether
        // is a second press.
        assertEquals(NavigationAction.Pop, page.onInput(FunctionId.BACK))
        assertEquals("cancelling calls nobody", 0, dialer.placeCalls)
    }

    @Test
    fun `should listen when it is seeded with voice`() = runTest {
        val page = page(DialSeed.Voice)
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(1, recognizer.listenCalls)
    }

    @Test
    fun `should show the microphone while listening`() {
        val page = page(DialSeed.Voice)

        val frame = page.render(1) as ProjectionFrame.LiveScreen
        assertEquals(IconId.MIC, frame.contentIconId)
        assertEquals("Say a name", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }

    @Test
    fun `should confirm the ranked candidates of what it heard`() = runTest {
        recognizer.willHear("llama a Ana")
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
        assertEquals(DialState.Confirming(expected, 0), page.state.value)
    }

    @Test
    fun `should search the address book with the courtesy prefix removed`() = runTest {
        recognizer.willHear("llama a Ana")
        directory.results = listOf(
            Contact(1, "Ana Ruiz", starred = false, numbers = listOf(PhoneNumber("600100100", PhoneKind.MOBILE)))
        )
        val page = page(DialSeed.Voice)
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals("ana", directory.lastQuery)
        assertEquals(DialState.Confirming(listOf(anaMobile), 0), page.state.value)
    }

    @Test
    fun `should treat silence as an outcome and not a failure`() = runTest {
        recognizer.willHearNothing()
        val page = page(DialSeed.Voice)
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(DialState.NothingUnderstood, page.state.value)
        val frame = page.render(1) as ProjectionFrame.LiveScreen
        assertEquals("I didn't catch that", frame.contentTextLine1)
        assertEquals(IconId.MIC, frame.contentIconId)
        assertEquals(IconId.LIVE_RETRY, frame.leftActionContentIconId)
    }

    @Test
    fun `should say when the name is not in the address book`() = runTest {
        recognizer.willHear("Pepe")
        directory.results = emptyList()
        val page = page(DialSeed.Voice)
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(DialState.NoMatch("Pepe"), page.state.value)
        val frame = page.render(1) as ProjectionFrame.LiveScreen
        assertEquals("Not in contacts", frame.contentTextLine1)
        assertEquals(IconId.MIC, frame.contentIconId)
        assertEquals(IconId.LIVE_RETRY, frame.leftActionContentIconId)
    }

    @Test
    fun `should say why a listen failed`() = runTest {
        val denied = page(DialSeed.Voice)
        recognizer.willFail(RecognitionFailure.NO_PERMISSION)
        backgroundScope.launch { denied.work() }
        runCurrent()

        assertEquals(DialState.Failed(RecognitionFailure.NO_PERMISSION), denied.state.value)
        val deniedFrame = denied.render(1) as ProjectionFrame.LiveScreen
        assertEquals("Microphone denied", deniedFrame.contentTextLine1)

        val missingRec = MockSpeechRecognizer().apply { willFail(RecognitionFailure.NO_RECOGNIZER) }
        val missing = CallDialPageProvider(
            seed = DialSeed.Voice,
            recognizer = missingRec,
            directory = directory,
            dialer = dialer,
            livePageFactory = { livePage },
            copy = englishClusterText,
            volumeSource = volume,
            activeCall = activeCall
        )
        backgroundScope.launch { missing.work() }
        runCurrent()

        assertEquals(DialState.Failed(RecognitionFailure.NO_RECOGNIZER), missing.state.value)
        val missingFrame = missing.render(1) as ProjectionFrame.LiveScreen
        assertEquals("No voice input", missingFrame.contentTextLine1)
    }

    @Test
    fun `should listen again from every dead end`() = runTest {
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
            val p = CallDialPageProvider(
                seed = DialSeed.Voice,
                recognizer = r,
                directory = d,
                dialer = dialer,
                livePageFactory = { livePage },
                copy = englishClusterText,
                volumeSource = volume,
                activeCall = activeCall
            )
            backgroundScope.launch { p.work() }
            runCurrent()

            assertEquals(1, r.listenCalls)

            r.willHear("llama a Ana")
            d.results = listOf(
                Contact(1, "Ana Ruiz", starred = false, numbers = listOf(PhoneNumber("600100100", PhoneKind.MOBILE)))
            )
            p.onInput(FunctionId.BACK)
            runCurrent()

            assertEquals(2, r.listenCalls)
            assertEquals(DialState.Confirming(listOf(anaMobile), 0), p.state.value)
        }
    }

    @Test
    fun `should go back to listening instead of leaving on back from a confirmation`() = runTest {
        recognizer.willHear("llama a Ana")
        directory.results = listOf(
            Contact(1, "Ana Ruiz", starred = false, numbers = listOf(PhoneNumber("600100100", PhoneKind.MOBILE)))
        )
        val page = page(DialSeed.Voice)
        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(DialState.Confirming(listOf(anaMobile), 0), page.state.value)

        val nav = page.onInput(FunctionId.BACK)
        assertEquals(NavigationAction.None, nav)
        assertEquals(DialState.Listening, page.state.value)
        runCurrent()

        assertEquals(2, recognizer.listenCalls)
    }

    @Test
    fun `should read out the candidate under the cursor`() = runTest {
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
    fun `should read the new name every time the cursor moves`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile, CallTarget("Ana Torres", "600300300", PhoneKind.MOBILE))))
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

        assertEquals(listOf("Ana Ruiz", "Ana Torres"), spoken)
    }

    @Test
    fun `should move the cursor with both names of the vertical keys`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile, anaHome)))

        page.onInput(FunctionId.VOLUME_DOWN)

        assertEquals(DialState.Confirming(listOf(anaMobile, anaHome), 1), page.state.value)
        assertEquals(0, volume.totalCalls)

        page.onInput(FunctionId.VOLUME_UP)

        assertEquals(DialState.Confirming(listOf(anaMobile, anaHome), 0), page.state.value)
        assertEquals(0, volume.totalCalls)
    }

    @Test
    fun `should stop the cursor at both ends`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile, anaHome)))

        page.onInput(FunctionId.PREVIOUS)
        assertEquals(DialState.Confirming(listOf(anaMobile, anaHome), 0), page.state.value)

        page.onInput(FunctionId.NEXT)
        assertEquals(DialState.Confirming(listOf(anaMobile, anaHome), 1), page.state.value)

        page.onInput(FunctionId.NEXT)
        assertEquals(DialState.Confirming(listOf(anaMobile, anaHome), 1), page.state.value)
    }

    @Test
    fun `should own the volume on every face but the confirmation`() = runTest {
        val pages = listOf(
            // Face 1: Listening
            CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer(), directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall),
            // Face 2: NothingUnderstood
            CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willHearNothing() }, directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            // Face 3: NoMatch
            CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willHear("Nobody") }, MockContactDirectory(), dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            // Face 4: Failed
            CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willFail(RecognitionFailure.NETWORK) }, directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            // Face 5: Placing
            CallDialPageProvider(
                DialSeed.Favorites(listOf(anaMobile)),
                recognizer,
                directory,
                object : CallDialer {
                    override suspend fun place(number: String): Boolean = CompletableDeferred<Boolean>().await()
                },
                { livePage },
                englishClusterText,
                volume,
                activeCall = activeCall
            ).also {
                backgroundScope.launch { it.work() }
                runCurrent()
                it.onInput(FunctionId.SELECT)
                runCurrent()
            },
            // Face 6: NotPlaced
            CallDialPageProvider(DialSeed.Favorites(listOf(anaMobile)), recognizer, directory, MockCallDialer(succeeds = false), { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
                it.onInput(FunctionId.SELECT)
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

            assertEquals("raises through both namings", initialRaise + 2, volume.raiseCalls)
            assertEquals("lowers through both namings", initialLower + 2, volume.lowerCalls)
            assertEquals("mutes", initialMute + 1, volume.muteCalls)
        }

        val pConfirming = CallDialPageProvider(DialSeed.Favorites(listOf(anaMobile, anaHome)), recognizer, directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall)
        val volCallsBefore = volume.totalCalls
        pConfirming.onInput(FunctionId.PREVIOUS)
        pConfirming.onInput(FunctionId.VOLUME_UP)
        pConfirming.onInput(FunctionId.NEXT)
        pConfirming.onInput(FunctionId.VOLUME_DOWN)
        assertEquals("confirming does not touch volume for vertical keys", volCallsBefore, volume.totalCalls)
    }

    @Test
    fun `should reset to the carousel on MENU from every face`() = runTest {
        val pages = listOf(
            "Listening" to CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer(), directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall),
            "Confirming" to CallDialPageProvider(DialSeed.Favorites(listOf(anaMobile)), recognizer, directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall),
            "NothingUnderstood" to CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willHearNothing() }, directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            "NoMatch" to CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willHear("Nobody") }, MockContactDirectory(), dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            "Failed" to CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willFail(RecognitionFailure.NETWORK) }, directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            "Placing" to CallDialPageProvider(
                DialSeed.Favorites(listOf(anaMobile)),
                recognizer,
                directory,
                object : CallDialer {
                    override suspend fun place(number: String): Boolean = CompletableDeferred<Boolean>().await()
                },
                { livePage },
                englishClusterText,
                volume,
                activeCall = activeCall
            ).also {
                backgroundScope.launch { it.work() }
                runCurrent()
                it.onInput(FunctionId.SELECT)
                runCurrent()
            },
            "NotPlaced" to CallDialPageProvider(DialSeed.Favorites(listOf(anaMobile)), recognizer, directory, MockCallDialer(succeeds = false), { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
                it.onInput(FunctionId.SELECT)
                runCurrent()
            }
        )

        for ((name, p) in pages) {
            assertEquals("$name resets on MENU", NavigationAction.Reset, p.onInput(FunctionId.MENU))
        }
    }

    @Test
    fun `should mirror exactly the keys it answers`() = runTest {
        val expectedMoving = mapOf(
            "Listening" to setOf(MirrorActionToken.CANCEL_DICTATION),
            "ConfirmingVoice" to setOf(MirrorActionToken.RETRY_DICTATION, MirrorActionToken.PLACE_CALL),
            "ConfirmingFav" to setOf(MirrorActionToken.GO_BACK, MirrorActionToken.PLACE_CALL),
            "NothingUnderstood" to setOf(MirrorActionToken.RETRY_DICTATION),
            "NoMatch" to setOf(MirrorActionToken.RETRY_DICTATION),
            "Failed" to setOf(MirrorActionToken.RETRY_DICTATION),
            "Placing" to emptySet(),
            "NotPlaced" to setOf(MirrorActionToken.RETRY_CALL)
        )

        val pages = listOf(
            "Listening" to CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer(), directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall),
            "ConfirmingVoice" to CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willHear("llama a Ana") }, directory.apply {
                results = listOf(Contact(1, "Ana Ruiz", false, listOf(PhoneNumber("600100100", PhoneKind.MOBILE))))
            }, dialer, { livePage }, englishClusterText, volume,
            activeCall = activeCall
        ).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            "ConfirmingFav" to CallDialPageProvider(DialSeed.Favorites(listOf(anaMobile)), recognizer, directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall),
            "NothingUnderstood" to CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willHearNothing() }, directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            "NoMatch" to CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willHear("Nobody") }, MockContactDirectory(), dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            "Failed" to CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willFail(RecognitionFailure.NETWORK) }, directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            "Placing" to CallDialPageProvider(
                DialSeed.Favorites(listOf(anaMobile)),
                recognizer,
                directory,
                object : CallDialer {
                    override suspend fun place(number: String): Boolean = CompletableDeferred<Boolean>().await()
                },
                { livePage },
                englishClusterText,
                volume,
                activeCall = activeCall
            ).also {
                backgroundScope.launch { it.work() }
                runCurrent()
                it.onInput(FunctionId.SELECT)
                runCurrent()
            },
            "NotPlaced" to CallDialPageProvider(DialSeed.Favorites(listOf(anaMobile)), recognizer, directory, MockCallDialer(succeeds = false), { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
                it.onInput(FunctionId.SELECT)
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
                assertEquals(
                    "$name has carousel vertical tokens",
                    setOf(MirrorActionToken.CAROUSEL_PREVIOUS, MirrorActionToken.CAROUSEL_NEXT),
                    verticalTokens
                )
            } else {
                assertEquals(
                    "$name has volume vertical tokens",
                    setOf(MirrorActionToken.VOLUME_UP, MirrorActionToken.VOLUME_DOWN),
                    verticalTokens
                )
            }

            assertEquals(
                "$name must have volume mute and menu escape",
                setOf(MirrorActionToken.VOLUME_MUTE, MirrorActionToken.OPEN_ROOT_MENU),
                actions.filter { it.slot == MirrorSlot.RIGHT || it.slot == MirrorSlot.HEADER }.map { it.token }.toSet()
            )
        }
    }

    @Test
    fun `should redraw on every transition`() = runTest {
        recognizer.willHear("llama a Ana")
        directory.results = listOf(
            Contact(1, "Ana Ruiz", starred = false, numbers = listOf(PhoneNumber("600100100", PhoneKind.MOBILE)))
        )
        dialer.succeeds = false
        val page = page(DialSeed.Voice)
        var ticks = 0
        backgroundScope.launch { page.invalidations.collect { ticks++ } }

        backgroundScope.launch { page.work() }
        runCurrent()
        assertEquals("Listening -> Confirming", 1, ticks)

        page.onInput(FunctionId.SELECT)
        runCurrent()
        assertEquals("Confirming -> Placing -> NotPlaced", 3, ticks)

        page.onInput(FunctionId.BACK)
        runCurrent()
        assertEquals("NotPlaced -> Placing -> NotPlaced", 5, ticks)
    }

    @Test
    fun `should never place a call when the listen is cancelled`() = runTest {
        recognizer.listensUntilReleased = true
        val page = page(DialSeed.Voice)
        val job = backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals(1, recognizer.listenCalls)
        job.cancel()
        runCurrent()

        recognizer.release("llama a Ana")
        runCurrent()

        assertEquals(0, dialer.placeCalls)
    }

    @Test
    fun `should never carry a content icon and a second line at once`() = runTest {
        val faces = listOf(
            "Listening" to { page(DialSeed.Voice) },
            "Confirming" to { page(DialSeed.Favorites(listOf(anaMobile))) },
            "NothingUnderstood" to {
                val r = MockSpeechRecognizer().apply { willHearNothing() }
                CallDialPageProvider(DialSeed.Voice, r, directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                    backgroundScope.launch { it.work() }
                }
            },
            "NoMatch" to {
                val r = MockSpeechRecognizer().apply { willHear("Nobody") }
                val d = MockContactDirectory()
                CallDialPageProvider(DialSeed.Voice, r, d, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                    backgroundScope.launch { it.work() }
                }
            },
            "Failed" to {
                val r = MockSpeechRecognizer().apply { willFail(RecognitionFailure.NETWORK) }
                CallDialPageProvider(DialSeed.Voice, r, directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                    backgroundScope.launch { it.work() }
                }
            },
            "Placing" to {
                val suspendingDialer = object : CallDialer {
                    override suspend fun place(number: String): Boolean = CompletableDeferred<Boolean>().await()
                }
                CallDialPageProvider(DialSeed.Favorites(listOf(anaMobile)), recognizer, directory, suspendingDialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                    backgroundScope.launch { it.work() }
                    runCurrent()
                    it.onInput(FunctionId.SELECT)
                }
            },
            "NotPlaced" to {
                val d = MockCallDialer(succeeds = false)
                CallDialPageProvider(DialSeed.Favorites(listOf(anaMobile)), recognizer, directory, d, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                    backgroundScope.launch { it.work() }
                    runCurrent()
                    it.onInput(FunctionId.SELECT)
                }
            }
        )

        for ((name, factory) in faces) {
            val p = factory()
            runCurrent()
            val frame = p.render(1) as ProjectionFrame.LiveScreen
            val hasIcon = frame.contentIconId != IconId.IC_NONE
            val hasLine2 = !frame.contentTextLine2.isNullOrEmpty()
            assertTrue(
                "$name face carries both content icon ($hasIcon) and second line ($hasLine2)",
                !(hasIcon && hasLine2)
            )
        }
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
    fun `should cue every move of the confirmation cursor`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile, anaHome)))
        val heard = cuesOf(page)

        page.onInput(FunctionId.NEXT)
        page.onInput(FunctionId.PREVIOUS)
        runCurrent()

        assertEquals(listOf(Cue.MOVE, Cue.MOVE), heard)
    }

    @Test
    fun `should cue both ends of the candidate list`() = runTest {
        val page = page(DialSeed.Favorites(listOf(anaMobile, anaHome)))
        val heard = cuesOf(page)

        page.onInput(FunctionId.PREVIOUS) // already at the top
        page.onInput(FunctionId.NEXT)     // moves to the second candidate
        page.onInput(FunctionId.NEXT)     // already at the bottom
        runCurrent()

        assertEquals(listOf(Cue.EDGE, Cue.MOVE, Cue.EDGE), heard)
    }

    /**
     * The volume is not a cursor, and its EDGE belongs to `VolumePageProvider`, the page that can
     * read the level — so the six faces that lend the vertical keys to the
     * volume announce nothing at all.
     */
    @Test
    fun `should stay quiet when the vertical keys are the volume`() = runTest {
        val pages = listOf(
            // Face: Listening
            CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer(), directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall),
            // Face: NothingUnderstood
            CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willHearNothing() }, directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            // Face: NoMatch
            CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willHear("Nobody") }, MockContactDirectory(), dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            // Face: Failed
            CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willFail(RecognitionFailure.NETWORK) }, directory, dialer, { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            // Face: Placing
            CallDialPageProvider(
                DialSeed.Favorites(listOf(anaMobile)),
                recognizer,
                directory,
                object : CallDialer {
                    override suspend fun place(number: String): Boolean = CompletableDeferred<Boolean>().await()
                },
                { livePage },
                englishClusterText,
                volume,
                activeCall = activeCall
            ).also {
                backgroundScope.launch { it.work() }
                runCurrent()
                it.onInput(FunctionId.SELECT)
                runCurrent()
            },
            // Face: NotPlaced
            CallDialPageProvider(DialSeed.Favorites(listOf(anaMobile)), recognizer, directory, MockCallDialer(succeeds = false), { livePage }, englishClusterText, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
                it.onInput(FunctionId.SELECT)
                runCurrent()
            }
        )

        for (p in pages) {
            val heard = cuesOf(p)

            p.onInput(FunctionId.PREVIOUS)
            p.onInput(FunctionId.VOLUME_UP)
            p.onInput(FunctionId.NEXT)
            p.onInput(FunctionId.VOLUME_DOWN)
            runCurrent()

            assertEquals(emptyList<Cue>(), heard)
        }
    }

    @Test
    fun `should cue a call that went`() = runTest {
        // SUCCESS, and before the Replace — captured rather than discarded, so both halves are
        // actually pinned.
        val page = page(DialSeed.Favorites(listOf(anaMobile)))
        val heard = cuesOf(page)
        val action = async { page.work() }
        runCurrent()

        page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(listOf(Cue.SUCCESS), heard)
        assertEquals(NavigationAction.Replace(livePage), action.await())
    }

    @Test
    fun `should cue a call that did not go`() = runTest {
        dialer.succeeds = false
        val page = page(DialSeed.Favorites(listOf(anaMobile)))
        val heard = cuesOf(page)
        backgroundScope.launch { page.work() }
        runCurrent()

        page.onInput(FunctionId.SELECT)
        runCurrent()

        assertEquals(listOf(Cue.FAILURE), heard)
    }

    @Test
    fun `should cue the three dead ends of the listen`() = runTest {
        // Silence is not a failure of the recognizer — it comes back as null on purpose — but the
        // vocabulary has eight words and none of them is "I did not hear you". The choice is
        // FAILURE or silence, and with a helmet on silence is indistinguishable from "still
        // listening", which leaves the rider talking to a page that stopped.
        val silentPage = CallDialPageProvider(
            DialSeed.Voice,
            MockSpeechRecognizer().apply { willHearNothing() },
            directory,
            dialer,
            { livePage },
            englishClusterText,
            volume,
            activeCall = activeCall
        )
        val silentHeard = cuesOf(silentPage)
        backgroundScope.launch { silentPage.work() }
        runCurrent()
        // The turn was handed over first, which is what the LISTEN in front of each of the three is.
        assertEquals(listOf(Cue.LISTEN, Cue.FAILURE), silentHeard)

        val noMatchPage = CallDialPageProvider(
            DialSeed.Voice,
            MockSpeechRecognizer().apply { willHear("Nobody") },
            MockContactDirectory(),
            dialer,
            { livePage },
            englishClusterText,
            volume,
            activeCall = activeCall
        )
        val noMatchHeard = cuesOf(noMatchPage)
        backgroundScope.launch { noMatchPage.work() }
        runCurrent()
        assertEquals(listOf(Cue.LISTEN, Cue.FAILURE), noMatchHeard)

        val failedPage = CallDialPageProvider(
            DialSeed.Voice,
            MockSpeechRecognizer().apply { willFail(RecognitionFailure.NETWORK) },
            directory,
            dialer,
            { livePage },
            englishClusterText,
            volume,
            activeCall = activeCall
        )
        val failedHeard = cuesOf(failedPage)
        backgroundScope.launch { failedPage.work() }
        runCurrent()
        assertEquals(listOf(Cue.LISTEN, Cue.FAILURE), failedHeard)
    }

    /**
     * *"So it beeps and you record a message after the signal"* — the rider's own words for the
     * dictation flow. The order is the whole point: a page that cues after
     * opening the microphone loses the first half second of every dictation.
     *
     * The microphone is opened and never released, so nothing this page would do **after** opening
     * it has happened when the assertion runs. A LISTEN in the list at that moment is therefore a
     * LISTEN that sounded first, and a page that cued after `listen()` leaves the list empty.
     */
    @Test
    fun `should cue the listen before the microphone opens`() = runTest {
        recognizer.listensUntilReleased = true
        val page = page(DialSeed.Voice)
        val heard = cuesOf(page)

        backgroundScope.launch { page.work() }
        runCurrent()

        assertEquals("the microphone is open and stays open", 1, recognizer.listenCalls)
        assertEquals(listOf(Cue.LISTEN), heard)
    }

    /**
     * `@Back` on any of the three dead ends writes [DialState.Listening] again, and a second listen
     * is a second turn handed over: without its own LISTEN the rider is talking to a page that never
     * told him it was ready, which is the one thing the tone exists to prevent.
     */
    @Test
    fun `should cue the listen again from every dead end`() = runTest {
        val deadEnds = listOf(
            // NothingUnderstood: he said nothing at all.
            MockSpeechRecognizer().apply { willHearNothing() } to directory,
            // NoMatch: he said a name that is not in the address book.
            MockSpeechRecognizer().apply { willHear("Nobody") } to MockContactDirectory(),
            // Failed: the recognizer itself gave up.
            MockSpeechRecognizer().apply { willFail(RecognitionFailure.NETWORK) } to directory
        )

        for ((deadEndRecognizer, book) in deadEnds) {
            val page = CallDialPageProvider(
                seed = DialSeed.Voice,
                recognizer = deadEndRecognizer,
                directory = book,
                dialer = dialer,
                livePageFactory = { livePage },
                copy = englishClusterText,
                volumeSource = volume,
                activeCall = activeCall
            )
            val heard = cuesOf(page)
            backgroundScope.launch { page.work() }
            runCurrent()
            assertEquals(listOf(Cue.LISTEN, Cue.FAILURE), heard)

            page.onInput(FunctionId.BACK)
            runCurrent()

            assertEquals(listOf(Cue.LISTEN, Cue.FAILURE, Cue.LISTEN, Cue.FAILURE), heard)
        }
    }

    @Test
    fun `should take its header from the resolver`() = runTest {
        val testCopy = ClusterTextResolver { "[${it.name}]" }
        val pages = listOf(
            CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer(), directory, dialer, { livePage }, testCopy, volume, activeCall = activeCall),
            CallDialPageProvider(DialSeed.Favorites(listOf(anaMobile)), recognizer, directory, dialer, { livePage }, testCopy, volume, activeCall = activeCall),
            CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willHearNothing() }, directory, dialer, { livePage }, testCopy, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willHear("Nobody") }, MockContactDirectory(), dialer, { livePage }, testCopy, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willFail(RecognitionFailure.NETWORK) }, directory, dialer, { livePage }, testCopy, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
            },
            CallDialPageProvider(
                DialSeed.Favorites(listOf(anaMobile)),
                recognizer,
                directory,
                object : CallDialer {
                    override suspend fun place(number: String): Boolean = CompletableDeferred<Boolean>().await()
                },
                { livePage },
                testCopy,
                volume,
                activeCall = activeCall
            ).also {
                backgroundScope.launch { it.work() }
                runCurrent()
                it.onInput(FunctionId.SELECT)
                runCurrent()
            },
            CallDialPageProvider(DialSeed.Favorites(listOf(anaMobile)), recognizer, directory, MockCallDialer(succeeds = false), { livePage }, testCopy, volume, activeCall = activeCall).also {
                backgroundScope.launch { it.work() }
                runCurrent()
                it.onInput(FunctionId.SELECT)
                runCurrent()
            }
        )

        for (p in pages) {
            val frame = p.render(1) as ProjectionFrame.LiveScreen
            assertEquals("[HEADER_PHONE]", frame.headerText)
        }
    }

    @Test
    fun `should take the four lines of its faces from the resolver`() = runTest {
        val testCopy = ClusterTextResolver { "[${it.name}]" }
        val listening = CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer(), directory, dialer, { livePage }, testCopy, volume, activeCall = activeCall)
        assertEquals("[DIAL_SAY_A_NAME]", (listening.render(1) as ProjectionFrame.LiveScreen).contentTextLine1)

        val noMatch = CallDialPageProvider(DialSeed.Voice, MockSpeechRecognizer().apply { willHear("Nobody") }, MockContactDirectory(), dialer, { livePage }, testCopy, volume, activeCall = activeCall).also {
            backgroundScope.launch { it.work() }
            runCurrent()
        }
        assertEquals("[DIAL_NOT_IN_CONTACTS]", (noMatch.render(1) as ProjectionFrame.LiveScreen).contentTextLine1)

        val placing = CallDialPageProvider(
            DialSeed.Favorites(listOf(anaMobile)),
            recognizer,
            directory,
            object : CallDialer {
                override suspend fun place(number: String): Boolean = CompletableDeferred<Boolean>().await()
            },
            { livePage },
            testCopy,
            volume,
            activeCall = activeCall
        ).also {
            backgroundScope.launch { it.work() }
            runCurrent()
            it.onInput(FunctionId.SELECT)
            runCurrent()
        }
        assertEquals("[CALL_CALLING]", (placing.render(1) as ProjectionFrame.LiveScreen).contentTextLine1)

        val notPlaced = CallDialPageProvider(DialSeed.Favorites(listOf(anaMobile)), recognizer, directory, MockCallDialer(succeeds = false), { livePage }, testCopy, volume, activeCall = activeCall).also {
            backgroundScope.launch { it.work() }
            runCurrent()
            it.onInput(FunctionId.SELECT)
            runCurrent()
        }
        assertEquals("[CALL_FAILED]", (notPlaced.render(1) as ProjectionFrame.LiveScreen).contentTextLine1)
    }

    @Test
    fun `should take the five number kinds from the resolver`() {
        val testCopy = ClusterTextResolver { "[${it.name}]" }
        val kinds = listOf(
            PhoneKind.MOBILE to "[PHONE_KIND_MOBILE]",
            PhoneKind.HOME to "[PHONE_KIND_HOME]",
            PhoneKind.WORK to "[PHONE_KIND_WORK]",
            PhoneKind.MAIN to "[PHONE_KIND_MAIN]",
            PhoneKind.OTHER to "[PHONE_KIND_OTHER]"
        )

        for ((kind, expected) in kinds) {
            val target = CallTarget("Test", "123", kind)
            val p = CallDialPageProvider(DialSeed.Favorites(listOf(target)), recognizer, directory, dialer, { livePage }, testCopy, volume, activeCall = activeCall)
            val frame = p.render(1) as ProjectionFrame.LiveScreen
            assertEquals(expected, frame.contentTextLine2)
        }
    }

    @Test
    fun `should take the ten listen failures from the resolver`() = runTest {
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

        val testCopy = ClusterTextResolver { "[${it.name}]" }
        for ((reason, line) in expected) {
            val rec = MockSpeechRecognizer().apply { willFail(reason) }
            val p = CallDialPageProvider(DialSeed.Voice, rec, directory, dialer, { livePage }, testCopy, volume, activeCall = activeCall)
            backgroundScope.launch { p.work() }
            runCurrent()

            assertEquals("$reason", line, (p.render(1) as ProjectionFrame.LiveScreen).contentTextLine1)
        }
    }

    @Test
    fun `BACK from Confirming to Listening emits empty list in speech`() = runTest {
        val page = page(DialSeed.Voice)
        directory.results = listOf(Contact(1L, "Ana Ruiz", false, listOf(PhoneNumber("600100100", PhoneKind.MOBILE))))
        recognizer.willHear("Ana")
        backgroundScope.launch { page.work() }
        runCurrent()
        assertEquals(DialState.Confirming(listOf(anaMobile), 0), page.state.value)

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

    @Test
    fun `speech emits CALL_NOT_PLACED on NotPlaced`() = runTest {
        dialer.succeeds = false
        val page = page(DialSeed.Favorites(listOf(anaMobile)))
        page.speech.test {
            assertEquals(listOf(SpeechRequest(SpeechToken.CALL_CONFIRM, SpeechArg.Text("Ana Ruiz"))), awaitItem())
            backgroundScope.launch { page.work() }
            runCurrent()
            page.onInput(FunctionId.SELECT)
            runCurrent()
            assertEquals(emptyList<SpeechRequest>(), awaitItem()) // from Placing
            assertEquals(listOf(SpeechRequest(SpeechToken.CALL_NOT_PLACED, SpeechArg.Text("Ana Ruiz"))), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
