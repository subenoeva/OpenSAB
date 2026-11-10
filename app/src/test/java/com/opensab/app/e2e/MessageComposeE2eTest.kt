package com.opensab.app.e2e

import com.opensab.domain.audio.Cue
import com.opensab.domain.contacts.CallTarget
import com.opensab.domain.contacts.Contact
import com.opensab.domain.contacts.PhoneKind
import com.opensab.domain.contacts.PhoneNumber
import com.opensab.domain.message.MessageMenuGates
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.CommandId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A new message from the handlebar, over the simulated cable and the graph Hilt builds: nothing
 * pending, the rider opens the message entry, says who, says what, hears it back and sends it.
 *
 * Every frame is compared byte for byte against a vector written out by hand from the layout
 * tables, never against the encoder: a test that asks the encoder what it should have encoded
 * agrees with a misplaced offset. `rr` is the `requestId`, which the session assigns.
 *
 * What no test here can reach is the last inch: `SmsManager` and its sent broadcast.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageComposeE2eTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val elena = CallTarget("Elena", "+34600111222", PhoneKind.MOBILE)

    private fun open(vararg favorites: CallTarget) =
        MessageMenuGates(voice = true, favorites = favorites.toList())

    /** Connected, handed over, and the carousel cursor on the message entry with nothing unread. */
    private suspend fun TestScope.onTheMessageEntry(gates: MessageMenuGates): SessionHarness {
        val harness = SessionHarness(backgroundScope)
        harness.messageMenuAccess.next = gates
        harness.contactDirectory.results = listOf(
            Contact(
                id = 1,
                displayName = elena.displayName,
                starred = true,
                numbers = listOf(PhoneNumber(elena.number, elena.kind, isPrimary = true))
            )
        )
        harness.recognizer.listensUntilReleased = true
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()
        var presses = 0
        while (!harness.btu.baseFrames().last().payload.containsAscii("Messages")) {
            check(presses++ < 8) { "the carousel never reached the message entry" }
            harness.btu.press(FunctionId.NEXT)
            runCurrent()
        }
        return harness
    }

    private suspend fun TestScope.press(harness: SessionHarness, functionId: FunctionId) {
        harness.btu.press(functionId)
        runCurrent()
    }

    /** Menu, by voice, "Elena" heard and confirmed: the dictation of the body is open. */
    private suspend fun TestScope.dictatingTo(harness: SessionHarness) {
        press(harness, FunctionId.SELECT)
        press(harness, FunctionId.SELECT)
        harness.recognizer.release("Elena")
        runCurrent()
        press(harness, FunctionId.SELECT)
    }

    private fun SessionHarness.pages(): List<String> = engine.stack.snapshot().map { it.name }

    private fun SessionHarness.painted(): WireFrame = btu.baseFrames().last()

    private fun assertWire(reason: String, expected: String, frame: WireFrame) {
        val bytes = expected.trim().split(Regex("\\s+")).map {
            if (it == "rr") frame.requestId.toByte() else it.toInt(16).toByte()
        }.toByteArray()
        assertArrayEquals(
            "$reason\nexpected: $expected\n  actual: ${frame.payload.joinToString(" ") { "%02X".format(it) }}",
            bytes,
            frame.payload
        )
    }

    @Test
    fun `the whole way from the carousel to a sent message, frame by frame`() = testScope.runTest {
        val harness = onTheMessageEntry(open(elena))
        val framesBefore = harness.btu.frames.size

        press(harness, FunctionId.SELECT)
        assertEquals(listOf("RootMenu", "MessageMenu"), harness.pages())
        assertWire("M1: the menu on its first row, both rows open", M1_MENU_BY_VOICE, harness.painted())

        press(harness, FunctionId.NEXT)
        assertWire("M2: the second row", M2_MENU_FAVORITES, harness.painted())
        press(harness, FunctionId.PREVIOUS)
        assertWire("M1 again", M1_MENU_BY_VOICE, harness.painted())

        press(harness, FunctionId.SELECT)
        assertEquals("MessageContactSelectPage", harness.engine.stack.peek()?.name)
        assertWire("M4: listening for a name", M4_SAY_A_NAME, harness.painted())
        assertEquals(1, harness.recognizer.listenCalls)
        assertTrue("LISTEN sounds before the microphone opens", Cue.LISTEN in harness.cuePlayer.played)

        harness.recognizer.release("Elena")
        runCurrent()
        assertWire("M5: the candidate, with a second line and therefore no icon", M5_CANDIDATE, harness.painted())

        press(harness, FunctionId.SELECT)
        assertEquals(
            "the composer replaces the selection, so BACK never lands on a stale candidate",
            listOf("RootMenu", "MessageMenu", "MessageComposePage"),
            harness.pages()
        )
        assertWire("M7: listening for the body", M7_SAY_YOUR_MESSAGE, harness.painted())
        assertEquals(2, harness.recognizer.listenCalls)

        val heardBefore = harness.spoken.utterances.size
        harness.recognizer.release("Almost there")
        runCurrent()
        assertWire("M8: the transcription under review", M8_REVIEW, harness.painted())
        assertEquals(
            "what he said is read back to him, once",
            listOf("MESSAGE_CONFIRM[Almost there]"),
            harness.spoken.utterances.drop(heardBefore).map { it.text }
        )
        assertTrue("nothing leaves before the rider says so", harness.smsSender.sentMessages.isEmpty())

        harness.smsSender.inFlight = CompletableDeferred()
        press(harness, FunctionId.SELECT)
        assertWire("M9: in flight", M9_SENDING, harness.painted())
        assertEquals(listOf(elena.number to "Almost there"), harness.smsSender.sentMessages)

        harness.smsSender.inFlight?.complete(Unit)
        runCurrent()
        assertWire("M10: sent", M10_SENT, harness.painted())

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals("and the screen goes back to the carousel on its own", listOf("RootMenu"), harness.pages())
        assertEquals("one message, sent once", 1, harness.smsSender.sendCount)

        val flow = harness.btu.frames.drop(framesBefore)
        assertTrue(
            "the flow is base screens only: it never raises a modal",
            flow.none { it.commandId == CommandId.POP_UP.value }
        )
        assertEquals("no requestId reused", flow.size, flow.map { it.requestId }.toSet().size)
    }

    @Test
    fun `a favourite skips the microphone on the way to the recipient`() = testScope.runTest {
        val harness = onTheMessageEntry(open(elena))
        press(harness, FunctionId.SELECT)
        press(harness, FunctionId.NEXT)

        press(harness, FunctionId.SELECT)

        assertEquals(0, harness.recognizer.listenCalls)
        assertWire("M6: the favourite, with cancel on the left instead of retry", M6_FAVORITE, harness.painted())

        press(harness, FunctionId.BACK)
        assertEquals(listOf("RootMenu", "MessageMenu"), harness.pages())
        assertWire("the menu kept its cursor", M2_MENU_FAVORITES, harness.painted())
    }

    @Test
    fun `without the permissions the menu opens and its rows lead nowhere`() = testScope.runTest {
        val harness = onTheMessageEntry(MessageMenuGates.CLOSED)

        press(harness, FunctionId.SELECT)
        assertWire("M3: no right arrow, and no byte for its tint", M3_MENU_SHUT, harness.painted())

        press(harness, FunctionId.SELECT)
        assertEquals(listOf("RootMenu", "MessageMenu"), harness.pages())
        assertEquals(0, harness.recognizer.listenCalls)
    }

    @Test
    fun `back on the menu returns to the carousel`() = testScope.runTest {
        val harness = onTheMessageEntry(open(elena))
        press(harness, FunctionId.SELECT)

        press(harness, FunctionId.BACK)

        assertEquals(listOf("RootMenu"), harness.pages())
        assertTrue(harness.painted().payload.containsAscii("Messages"))
    }

    @Test
    fun `back while saying the name closes the microphone and returns to the menu`() = testScope.runTest {
        val harness = onTheMessageEntry(open(elena))
        press(harness, FunctionId.SELECT)
        press(harness, FunctionId.SELECT)

        press(harness, FunctionId.BACK)

        assertEquals(listOf("RootMenu", "MessageMenu"), harness.pages())
        harness.recognizer.release("Elena")
        runCurrent()
        assertEquals(
            "a listen cut by BACK must not come back and pick a recipient",
            listOf("RootMenu", "MessageMenu"),
            harness.pages()
        )
        assertWire("and the menu is what the cluster shows", M1_MENU_BY_VOICE, harness.painted())
    }

    @Test
    fun `back on a candidate heard by voice listens again`() = testScope.runTest {
        val harness = onTheMessageEntry(open(elena))
        press(harness, FunctionId.SELECT)
        press(harness, FunctionId.SELECT)
        harness.recognizer.release("Elena")
        runCurrent()

        press(harness, FunctionId.BACK)

        assertEquals("MessageContactSelectPage", harness.engine.stack.peek()?.name)
        assertEquals(2, harness.recognizer.listenCalls)
        assertWire("back on the name prompt", M4_SAY_A_NAME, harness.painted())
    }

    @Test
    fun `back while dictating returns to the menu and sends nothing`() = testScope.runTest {
        val harness = onTheMessageEntry(open(elena))
        dictatingTo(harness)

        press(harness, FunctionId.BACK)

        assertEquals(listOf("RootMenu", "MessageMenu"), harness.pages())
        harness.recognizer.release("said to nobody")
        runCurrent()
        assertEquals(listOf("RootMenu", "MessageMenu"), harness.pages())
        assertTrue(harness.smsSender.sentMessages.isEmpty())
    }

    @Test
    fun `back on the review dictates again and sends nothing`() = testScope.runTest {
        val harness = onTheMessageEntry(open(elena))
        dictatingTo(harness)
        harness.recognizer.release("Almost there")
        runCurrent()

        press(harness, FunctionId.BACK)

        assertEquals("MessageComposePage", harness.engine.stack.peek()?.name)
        assertEquals(3, harness.recognizer.listenCalls)
        assertWire("back on the body prompt", M7_SAY_YOUR_MESSAGE, harness.painted())
        assertTrue(harness.smsSender.sentMessages.isEmpty())
    }

    @Test
    fun `menu on the review drops the whole flow and sends nothing`() = testScope.runTest {
        val harness = onTheMessageEntry(open(elena))
        dictatingTo(harness)
        harness.recognizer.release("Almost there")
        runCurrent()

        press(harness, FunctionId.MENU)
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(listOf("RootMenu"), harness.pages())
        assertTrue(harness.smsSender.sentMessages.isEmpty())
    }

    /** Nothing says "sent" that was not: a refused send offers the retry instead. */
    @Test
    fun `a refused send says so, and select tries again`() = testScope.runTest {
        val harness = onTheMessageEntry(open(elena))
        harness.smsSender.nextResult = false
        dictatingTo(harness)
        harness.recognizer.release("Almost there")
        runCurrent()

        press(harness, FunctionId.SELECT)
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals("MessageComposePage", harness.engine.stack.peek()?.name)
        assertWire("M11: not sent", M11_NOT_SENT, harness.painted())
        assertFalse(harness.btu.baseFrames().any { it.payload.contentEquals(wire(M10_SENT, it)) })

        harness.smsSender.nextResult = true
        press(harness, FunctionId.SELECT)

        assertEquals(
            listOf(elena.number to "Almost there", elena.number to "Almost there"),
            harness.smsSender.sentMessages
        )
        assertWire("and this time it is", M10_SENT, harness.painted())
    }

    @Test
    fun `select while dictating opens the quick replies, and one of them is sent`() = testScope.runTest {
        val harness = onTheMessageEntry(open(elena))
        dictatingTo(harness)

        press(harness, FunctionId.SELECT)
        assertWire("M12: the first quick reply of two", M12_QUICK_REPLY, harness.painted())

        press(harness, FunctionId.SELECT)

        assertEquals(listOf(elena.number to "On my way"), harness.smsSender.sentMessages)
        harness.recognizer.release("said too late")
        runCurrent()
        assertEquals("a listen left behind sends nothing more", 1, harness.smsSender.sendCount)
    }

    @Test
    fun `an incoming call mid-dictation closes the microphone and sends nothing`() = testScope.runTest {
        val harness = onTheMessageEntry(open(elena))
        dictatingTo(harness)

        harness.callDriver.post(callerName = "Carlos")
        runCurrent()

        assertEquals("CallPage", harness.engine.stack.peek()?.name)
        harness.recognizer.release("said to nobody")
        runCurrent()
        assertEquals("CallPage", harness.engine.stack.peek()?.name)
        assertTrue(harness.smsSender.sentMessages.isEmpty())
    }

    private fun wire(expected: String, frame: WireFrame): ByteArray =
        expected.trim().split(Regex("\\s+")).map {
            if (it == "rr") frame.requestId.toByte() else it.toInt(16).toByte()
        }.toByteArray()

    private companion object {
        /** `0B 08 "Messages" 00`: BLUE header, the text, PURE_WHITE. */
        const val HEADER = "01 rr 0B 08 4D 65 73 73 61 67 65 73 00"

        const val M1_MENU_BY_VOICE =
            "$HEADER 01 0A 00 04 01 01 01 02 01 07 00 08 42 79 20 76 6F 69 63 65 00 00"
        const val M2_MENU_FAVORITES =
            "$HEADER 01 0A 03 01 00 01 01 02 01 67 00 09 46 61 76 6F 72 69 74 65 73 00 00"
        const val M3_MENU_SHUT =
            "$HEADER 01 0A 00 04 01 01 01 00 07 00 08 42 79 20 76 6F 69 63 65 00 00"
        const val M4_SAY_A_NAME =
            "$HEADER 02 0B 01 01 01 00 0A 00 0C 07 00 0A 53 61 79 20 61 20 6E 61 6D 65 00 00"
        const val M5_CANDIDATE =
            "$HEADER 02 0B 01 01 06 00 0A 02 01 0E 00 03 0C 00 05 45 6C 65 6E 61 00 06 4D 6F 62 69 6C 65 00"
        const val M6_FAVORITE =
            "$HEADER 02 0B 01 01 01 00 0A 02 01 0E 00 03 0C 00 05 45 6C 65 6E 61 00 06 4D 6F 62 69 6C 65 00"
        const val M7_SAY_YOUR_MESSAGE =
            "$HEADER 02 0B 01 01 01 00 0A 02 01 0E 00 0A 0C 07 00 " +
                "10 53 61 79 20 79 6F 75 72 20 6D 65 73 73 61 67 65 00 00"
        const val M8_REVIEW =
            "$HEADER 02 0B 01 01 06 00 0A 02 01 07 00 03 0C 08 00 " +
                "0C 41 6C 6D 6F 73 74 20 74 68 65 72 65 00 00"
        const val M9_SENDING =
            "$HEADER 02 0B 00 00 0C 59 00 0A 53 65 6E 64 69 6E 67 2E 2E 2E 00 00"
        const val M10_SENT =
            "$HEADER 02 0B 00 00 0C 09 00 04 53 65 6E 74 00 00"
        const val M11_NOT_SENT =
            "$HEADER 02 0B 01 01 06 00 0A 02 01 07 00 03 0C 56 00 08 4E 6F 74 20 73 65 6E 74 00 00"
        const val M12_QUICK_REPLY =
            "$HEADER 01 0A 00 04 01 01 01 02 01 00 09 4F 6E 20 6D 79 20 77 61 79 00 00"
    }
}
