package com.opensab.app.e2e

import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.input.HandlebarEvent
import com.opensab.sab.protocol.input.InputSource
import com.opensab.sab.protocol.projection.CommandId
import com.opensab.sab.session.assignment.DefaultAssignmentManager
import com.opensab.sab.session.crypto.DefaultSecuritySessionManager
import com.opensab.sab.session.crypto.InMemorySecureKeyStore
import com.opensab.sab.session.input.DefaultInputDispatcher
import com.opensab.sab.session.projection.DefaultControlPointGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scripted BTU, checked against the very production code that will talk to it.
 *
 * Every assertion here goes through a real collaborator — `DefaultSecuritySessionManager`,
 * `DefaultAssignmentManager`, `DefaultInputDispatcher` — and never against the simulator's own
 * bookkeeping. A simulator verified against itself would agree with itself all the way to the
 * garage, which is exactly how a codec suite once stayed green against an invented format.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BtuSimulatorTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun `completes the security handshake against the real session manager`() = testScope.runTest {
        val btu = BtuSimulator()
        btu.boot()
        val sessionManager = DefaultSecuritySessionManager(InMemorySecureKeyStore())

        val result = sessionManager.establishSession(MAC, btu.transport)

        assertTrue(
            "The simulator has to answer the RSA exchange and the key validation: ${result.exceptionOrNull()}",
            result.isSuccess
        )
        assertTrue(
            "The session must carry the AES key the BTU handed over",
            result.getOrThrow().aesKey.contentEquals(btu.aesKey)
        )
    }

    @Test
    fun `answers one receipt per ControlPoint write`() = testScope.runTest {
        val btu = BtuSimulator()
        btu.boot()
        val gateway = DefaultControlPointGateway()
        gateway.start(btu.transport, backgroundScope)

        // Three frames of the shape REQUEST_SAB_MODE takes: the smallest write on the ControlPoint.
        repeat(3) { index ->
            val requestId = index + 1
            gateway.dispatch(
                transport = btu.transport,
                commandId = CommandId.REQUEST_SAB_MODE,
                requestId = requestId,
                payload = byteArrayOf(0x0A, requestId.toByte())
            )
        }

        assertEquals("Three writes must reach the BTU", 3, btu.frames.size)
        assertEquals(
            "Each write must be acknowledged with its own requestId",
            listOf(1, 2, 3),
            btu.requestIds
        )
    }

    @Test
    fun `answers one assignment result per record`() = testScope.runTest {
        val btu = BtuSimulator()
        btu.boot()

        val report = DefaultAssignmentManager()
            .executeAssignment(btu.transport, backgroundScope)
            .getOrThrow()

        // 27 correlated records plus END_ASSIGNMENT, which the BTU answers with an event and never
        // with an AssignmentResult.
        assertEquals("The standard batch is 27 records", 27, report.written)
        assertEquals("Every record must come back accepted", 27, report.accepted)
        assertTrue(
            "A booted simulator answers every record: rejected=${report.rejected}, timedOut=${report.timedOut}",
            report.isClean
        )
        assertEquals(
            "END_ASSIGNMENT goes out as a write of its own, after the batch",
            28,
            btu.assignmentRecords.size
        )
    }

    @Test
    fun `pushes a mode notification the dispatcher decodes`() = testScope.runTest {
        val btu = BtuSimulator()
        btu.boot()
        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        dispatcher.startListening(btu.transport, backgroundScope)

        btu.grantSabMode()
        runCurrent()

        assertEquals(
            "The 0x09 notification is the only thing that grants the screen",
            DashboardMode.SAB_MODE,
            dispatcher.currentMode.value
        )
    }

    @Test
    fun `pushes a handlebar press the dispatcher decodes`() = testScope.runTest {
        val btu = BtuSimulator()
        btu.boot()
        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        dispatcher.startListening(btu.transport, backgroundScope)

        val seen = mutableListOf<HandlebarEvent>()
        backgroundScope.launch { dispatcher.events.collect { seen += it } }
        runCurrent()

        btu.press(FunctionId.SELECT)
        runCurrent()

        // Not assertEquals on the event: `timestamp` is part of ButtonPressed's equality and is
        // stamped at construction, so two presses of the same button are never equal.
        assertEquals("Exactly one event, decoded from one notification", 1, seen.size)
        val pressed = seen.single()
        assertTrue("A press must arrive decoded, not as raw bytes: $pressed", pressed is HandlebarEvent.ButtonPressed)
        assertEquals(FunctionId.SELECT, (pressed as HandlebarEvent.ButtonPressed).functionId)
        assertEquals(
            "A press over the wire is a handlebar press, never a mirror one",
            InputSource.HANDLEBAR,
            pressed.source
        )
    }

    private companion object {
        const val MAC = "02:00:00:00:00:AB"
    }
}
