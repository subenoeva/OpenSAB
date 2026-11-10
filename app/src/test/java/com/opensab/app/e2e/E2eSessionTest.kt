package com.opensab.app.e2e

import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.BodyType
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.session.projection.QueuedPopUp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A whole session, from the pipeline to the frame the cluster paints, over the graph Hilt builds.
 *
 * The presses here are not injected into the engine: they arrive as notifications on
 * `CHAR_FUNCTION_NOTIFICATION` and are decoded by the real `DefaultInputDispatcher`. That is the
 * difference between testing the engine and testing the session: the ownership defects of this
 * repository were all invisible to unit tests because every piece did the right thing on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class E2eSessionTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun `the pipeline reaches Connected through the module graph`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)

        val result = harness.connect()

        assertTrue("The pipeline must complete: ${result.exceptionOrNull()}", result.isSuccess)
        val connected = result.getOrThrow()
        assertEquals("0536", connected.vehicle.vehicleInfo.seriesCode)
        assertEquals(SessionHarness.MAC, connected.session.macAddress)
        assertTrue("The CTS server is part of the pipeline, not an afterthought", harness.cts.isRunning())
        assertTrue(
            "The cluster's limits have to be adopted before any adapter starts",
            harness.capabilities.capabilities.value.supportsModals
        )
    }

    @Test
    fun `the cluster handing the screen over paints the carousel`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()

        harness.handOverScreen()

        val frame = harness.btu.baseFrames().lastOrNull()
        assertNotNull("Granting SAB_MODE has to put a frame on the cluster", frame)
        assertEquals(
            "The root page is the carousel, and the carousel is a MENU body",
            BodyType.MENU.value,
            frame!!.bodyType
        )
        assertTrue(
            "The header of a MENU is the title of the selected entry, and the cursor starts at Weather",
            frame.payload.containsAscii("Weather")
        )
    }

    /**
     * The press travels the whole way: notification -> InputDispatcher -> BaseStateManager ->
     * RootMenuPageProvider -> MusicPageProvider -> frame. Nothing here calls into the engine.
     */
    @Test
    fun `a handlebar press over the wire opens the music page`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()

        // Weather -> Navigation -> Phone -> Messages -> Music. The cursor clamps, it does not wrap.
        repeat(4) {
            harness.btu.press(FunctionId.NEXT)
            runCurrent()
        }
        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        val frame = harness.btu.baseFrames().last()
        assertEquals(
            "SELECT on Music must open a page, and a page is a LIVE_SCREEN body",
            BodyType.LIVE_SCREEN.value,
            frame.bodyType
        )
    }

    /**
     * Receipts correlate by requestId alone, so a reused id makes one acknowledgement answer for two
     * frames — which is what independent generators over one ControlPoint did.
     */
    @Test
    fun `every write gets one receipt and no requestId is ever reused`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        repeat(3) {
            harness.btu.press(FunctionId.NEXT)
            runCurrent()
        }

        val ids = harness.btu.requestIds
        assertTrue("The session has to have written something", ids.isNotEmpty())
        assertEquals(
            "A requestId reused inside one session makes one Receipt answer for two frames: $ids",
            ids.size,
            ids.toSet().size
        )
    }

    /**
     * The modal layer is alive, complete and deliberately without a production caller, so the
     * harness is the only thing that can drive it end to end.
     */
    @Test
    fun `a popup posted while frames stream restores the page underneath`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        val beforeThePopUp = harness.btu.baseFrames().size

        harness.engine.popupManager.post(
            QueuedPopUp(id = "nav-alert", message = "Turn right in 200 m", icon = IconId.NAVIGATION, duration = 5.seconds)
        )
        runCurrent()
        assertEquals(
            "A modal is a POP_UP frame of its own, not a base screen",
            1,
            harness.btu.frames.count { it.commandId == 0x02 }
        )

        advanceTimeBy(5_001.milliseconds)
        runCurrent()

        assertTrue(
            "When the modal drains, the page underneath has to be painted again",
            harness.btu.baseFrames().size > beforeThePopUp
        )
    }

    /**
     * Byte 3 of a POP_UP is its content icon, a fixed field. It is the emitter's to
     * declare; a message popup painted with the incoming-call handset is a lie on the dash.
     */
    @Test
    fun `a popup carries on the wire the icon its emitter declared`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()

        harness.engine.popupManager.post(
            QueuedPopUp(id = "message", message = "Alice", icon = IconId.INCOMING_MESSAGE)
        )
        runCurrent()

        val popUp = harness.btu.frames.single { it.commandId == 0x02 }.payload
        assertEquals(IconId.INCOMING_MESSAGE.id, popUp[3].toInt() and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a popup cannot be queued without a content icon`() {
        QueuedPopUp(id = "blank", message = "Alice", icon = IconId.IC_NONE)
    }

    /**
     * The BTU stays silent on a frame, the 2000 ms watchdog fires, and the ControlPoint has to come
     * back.
     *
     * A wedged queue is the worst failure this layer has: one unanswered frame would take the rest
     * of the ride with it, and it would look like a cluster that simply stopped updating.
     */
    @Test
    fun `a frame the BTU never acknowledges does not wedge the ControlPoint`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        harness.btu.silentCommands = setOf(0x02) // POP_UP

        var outcome: Result<*>? = null
        val silent = launch {
            outcome = harness.screenProjector.postPopUp(
                QueuedPopUp(id = "unanswered", message = "no receipt for this one", icon = IconId.INCOMING_MESSAGE)
            )
        }
        advanceTimeBy(2_001.milliseconds)
        runCurrent()
        silent.join()

        assertTrue("An unanswered frame has to surface as a failure, not as a success", outcome!!.isFailure)

        harness.btu.silentCommands = emptySet()
        val paintedBefore = harness.btu.baseFrames().size
        harness.btu.press(FunctionId.NEXT)
        runCurrent()

        assertTrue(
            "And the next frame still has to get through: the queue releases the command it lost",
            harness.btu.baseFrames().size > paintedBefore
        )
    }

}
