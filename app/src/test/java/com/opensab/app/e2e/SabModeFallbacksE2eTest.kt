package com.opensab.app.e2e

import com.opensab.cluster.mirror.MirrorDelivery
import com.opensab.cluster.mirror.MirrorState
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.BodyType
import com.opensab.sab.protocol.projection.CommandId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A cluster that acknowledges REQUEST_SAB_MODE and does not hand the screen over, read off the wire.
 *
 * The first block pins what a session writes with both developer switches off, which is what every
 * rider gets: two requests, five seconds apart, and not one frame before the 0x09. The rest is what
 * each switch adds to that, and nothing else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SabModeFallbacksE2eTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun BtuSimulator.sabModeRequests(): List<WireFrame> =
        frames.filter { it.commandId == CommandId.REQUEST_SAB_MODE.value }

    private suspend fun SessionHarness.choose(
        rerequestOnHuMode: Boolean = false,
        projectWithoutGrant: Boolean = false
    ) = settings.updateSettings {
        it.copy(
            sabModeRerequestOnHuMode = rerequestOnHuMode,
            projectWithoutSabModeGrant = projectWithoutGrant
        )
    }

    // --- Both switches off ----------------------------------------------------------------------

    @Test
    fun `with both switches off a silent cluster gets two requests five seconds apart and no frame`() =
        testScope.runTest {
            val harness = SessionHarness(backgroundScope)
            // The BTU the field logs show: a HU_MODE after every request. Off means it is ignored.
            harness.btu.echoHuModeOnSabModeRequest = true

            harness.connect().getOrThrow()
            runCurrent()
            assertEquals("one request at session open", 1, harness.btu.sabModeRequests().size)

            advanceTimeBy(4_999.milliseconds)
            runCurrent()
            assertEquals("nothing more inside the window", 1, harness.btu.sabModeRequests().size)

            advanceTimeBy(2.milliseconds)
            runCurrent()
            assertEquals("the second one at 5 s", 2, harness.btu.sabModeRequests().size)

            advanceTimeBy(120.seconds)
            runCurrent()

            val requests = harness.btu.sabModeRequests()
            assertEquals("and never a third", 2, requests.size)
            requests.forEach {
                assertArrayEquals(byteArrayOf(0x0A, it.requestId.toByte()), it.payload)
            }
            assertEquals(
                "Only requests and the status bar keepalive reach the ControlPoint before a grant",
                setOf(CommandId.REQUEST_SAB_MODE.value, CommandId.ACTIVE_INDICATORS_CONTROL.value),
                harness.btu.frames.map { it.commandId }.toSet()
            )
            assertEquals(
                "A cluster that never granted leaves the d-pad hint on the mirror",
                MirrorState.ClusterOwnsScreen(handoverPending = false, grantOverdue = true),
                harness.mirror.state.value
            )
        }

    @Test
    fun `with both switches off the first frame is the carousel, written once, on the 0x09`() =
        testScope.runTest {
            val harness = SessionHarness(backgroundScope)
            harness.connect().getOrThrow()
            advanceTimeBy(30.seconds)
            runCurrent()
            assertTrue(harness.btu.baseFrames().isEmpty())

            harness.handOverScreen()
            runCurrent()

            val frame = harness.btu.baseFrames().single()
            assertEquals(BodyType.MENU.value, frame.bodyType)
            assertEquals(MirrorDelivery.Confirmed, (harness.mirror.state.value as MirrorState.Projecting).delivery)
            assertEquals(2, harness.btu.sabModeRequests().size)
        }

    // --- Re-request on HU_MODE ------------------------------------------------------------------

    @Test
    fun `a HU_MODE before the first grant is answered with another request`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.choose(rerequestOnHuMode = true)
        harness.connect().getOrThrow()
        advanceTimeBy(1.seconds)

        harness.btu.returnToHuMode()
        runCurrent()

        assertEquals(2, harness.btu.sabModeRequests().size)
        assertTrue("Asking is not projecting", harness.btu.baseFrames().isEmpty())
    }

    /** The HU_MODE of a real BTU lands before the Receipt of the request that opens the session. */
    @Test
    fun `the HU_MODE that answers the opening request is not missed`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.choose(rerequestOnHuMode = true)
        harness.btu.echoHuModeOnSabModeRequest = true

        harness.connect().getOrThrow()
        runCurrent()

        assertEquals(2, harness.btu.sabModeRequests().size)
    }

    @Test
    fun `a BTU that echoes HU_MODE to every request is asked seven times at most`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.choose(rerequestOnHuMode = true)
        harness.btu.echoHuModeOnSabModeRequest = true
        harness.connect().getOrThrow()

        advanceTimeBy(900.milliseconds)
        runCurrent()
        assertEquals("the re-requests are a second apart", 2, harness.btu.sabModeRequests().size)

        advanceTimeBy(120.seconds)
        runCurrent()

        assertEquals(
            "opening, five inside the window, one at the timeout, and then it stops listening",
            7,
            harness.btu.sabModeRequests().size
        )
        assertTrue(harness.btu.baseFrames().isEmpty())
    }

    @Test
    fun `after the first grant a return to HU_MODE is never re-requested`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.choose(rerequestOnHuMode = true, projectWithoutGrant = true)
        harness.connect().getOrThrow()
        advanceTimeBy(1.seconds)
        harness.handOverScreen()
        runCurrent()
        val written = harness.btu.baseFrames().size

        repeat(3) {
            advanceTimeBy(1.seconds)
            harness.btu.returnToHuMode()
            runCurrent()
        }
        advanceTimeBy(120.seconds)
        runCurrent()

        assertEquals("The rider took the screen back: one request, the opening one", 1, harness.btu.sabModeRequests().size)
        assertEquals("and nothing is projected over the tachometer", written, harness.btu.baseFrames().size)
        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = false), harness.mirror.state.value)
    }

    // --- Projecting without the 0x09 ------------------------------------------------------------

    @Test
    fun `the carousel goes out at the timeout although no 0x09 arrived`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.choose(projectWithoutGrant = true)
        harness.connect().getOrThrow()

        advanceTimeBy(4_999.milliseconds)
        runCurrent()
        assertTrue("Not one frame inside the window", harness.btu.baseFrames().isEmpty())

        advanceTimeBy(2.milliseconds)
        runCurrent()

        val frame = harness.btu.baseFrames().single()
        assertEquals(BodyType.MENU.value, frame.bodyType)
        assertEquals("the request at the timeout is still written", 2, harness.btu.sabModeRequests().size)
        assertTrue(
            "and it is written before the frame",
            harness.btu.frames.indexOf(harness.btu.sabModeRequests().last()) < harness.btu.frames.indexOf(frame)
        )
        assertEquals(
            "The BTU took the bytes; nobody knows whether the cluster shows them",
            MirrorDelivery.HandoverUnconfirmed,
            (harness.mirror.state.value as MirrorState.Projecting).delivery
        )
    }

    @Test
    fun `a handlebar press moves the carousel while the grant is assumed, HU_MODE or not`() =
        testScope.runTest {
            val harness = SessionHarness(backgroundScope)
            harness.choose(projectWithoutGrant = true)
            harness.connect().getOrThrow()
            advanceTimeBy(6.seconds)
            runCurrent()

            harness.btu.returnToHuMode()
            harness.btu.press(FunctionId.NEXT)
            runCurrent()

            assertEquals(2, harness.btu.baseFrames().size)
        }

    /**
     * The late 0x09: no second root, and the page the rider is on is written again although it is
     * byte for byte the one already sent, because a cluster in HU_MODE may have discarded that one.
     */
    @Test
    fun `the late 0x09 writes the current page again and does not mount the root twice`() =
        testScope.runTest {
            val harness = SessionHarness(backgroundScope)
            harness.choose(projectWithoutGrant = true)
            harness.connect().getOrThrow()
            advanceTimeBy(6.seconds)
            harness.btu.press(FunctionId.NEXT)
            runCurrent()
            val root = harness.engine.stack.snapshot().single()
            val blind = harness.btu.baseFrames()

            harness.handOverScreen()
            runCurrent()

            val all = harness.btu.baseFrames()
            assertEquals("exactly one frame for the confirmation", blind.size + 1, all.size)
            assertArrayEquals(
                "the same page at the same cursor, under a new requestId",
                blind.last().payload.withoutRequestId(),
                all.last().payload.withoutRequestId()
            )
            assertSame(root, harness.engine.stack.snapshot().single())
            assertEquals(MirrorDelivery.Confirmed, (harness.mirror.state.value as MirrorState.Projecting).delivery)
            assertEquals("no requestId is reused", all.size, all.map { it.requestId }.toSet().size)
        }

    @Test
    fun `a genuine HU_MODE after the late 0x09 suspends emission for good`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.choose(projectWithoutGrant = true)
        harness.connect().getOrThrow()
        advanceTimeBy(6.seconds)
        harness.handOverScreen()
        runCurrent()
        val written = harness.btu.baseFrames().size

        harness.btu.returnToHuMode()
        harness.btu.press(FunctionId.NEXT)
        advanceTimeBy(120.seconds)
        runCurrent()

        assertEquals(written, harness.btu.baseFrames().size)
        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = false), harness.mirror.state.value)
        assertEquals("nor is the screen asked for again", 2, harness.btu.sabModeRequests().size)
    }

    @Test
    fun `a 0x09 inside the window leaves nothing to assume`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.choose(projectWithoutGrant = true)
        harness.connect().getOrThrow()
        advanceTimeBy(1.seconds)
        harness.handOverScreen()
        runCurrent()

        advanceTimeBy(120.seconds)
        runCurrent()

        assertEquals(1, harness.btu.baseFrames().size)
        assertEquals(1, harness.btu.sabModeRequests().size)
        assertEquals(MirrorDelivery.Confirmed, (harness.mirror.state.value as MirrorState.Projecting).delivery)
    }

    // --- The switches are read at session open --------------------------------------------------

    @Test
    fun `a switch flipped with the session live waits for the next one`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.choose(rerequestOnHuMode = true, projectWithoutGrant = true)
        advanceTimeBy(1.seconds)

        harness.btu.returnToHuMode()
        advanceTimeBy(120.seconds)
        runCurrent()

        assertEquals(2, harness.btu.sabModeRequests().size)
        assertTrue(harness.btu.baseFrames().isEmpty())
    }

    private fun ByteArray.withoutRequestId(): ByteArray = copyOfRange(0, 1) + copyOfRange(2, size)
}
