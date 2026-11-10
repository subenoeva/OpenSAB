package com.opensab.app.e2e

import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.BodyType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A call that starts ringing before the cluster hands the screen over.
 *
 * The order is not exotic: the rider is on the phone, switches the ignition on, and the cluster
 * grants SAB_MODE with the call already up. The interrupt page is mounted whatever the mode is,
 * deliberately — `projectActivePage` is what declines to write outside SAB_MODE, so the page is
 * already there when the screen arrives. What was missing is what sits
 * underneath it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InterruptBeforeHandoverE2eTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private suspend fun SessionHarness.ringWhileTheClusterKeepsTheScreen() {
        connect().getOrThrow()
        callDriver.post(callerName = "Ana", callerNumber = "+34600000000")
        handOverScreen()
    }

    /**
     * `onModeChanged` mounted the root only `if (stack.isEmpty)`, and `isEmpty` counts both stacks.
     * With the call already claiming the interrupt stack the default stack stayed empty, so the
     * session ran with a call page and nothing underneath it.
     */
    @Test
    fun `a call ringing in HU_MODE leaves a root under it once the screen is granted`() =
        testScope.runTest {
            val harness = SessionHarness(backgroundScope)

            harness.ringWhileTheClusterKeepsTheScreen()
            runCurrent()

            assertEquals(
                "The carousel has to be underneath the call, bottom of the stack first",
                listOf("RootMenu", "CallPage"),
                harness.engine.stack.snapshot().map { it.name }
            )
        }

    /**
     * The press that turns the hole into a dead cluster.
     *
     * MENU on the call page is `Reset`, which drops the interrupt stack and unwinds the default one
     * to its root — and with no root there, `resetToRoot` returns null, `projectActivePage` leaves
     * on `peek() == null`, and the cluster keeps the last frame it was given while every further
     * press is silently dropped. Nothing recovers until the call ends.
     */
    @Test
    fun `MENU during that call returns the rider to the carousel`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.ringWhileTheClusterKeepsTheScreen()
        runCurrent()

        harness.btu.press(FunctionId.MENU)
        runCurrent()

        val frame = harness.btu.baseFrames().last()
        assertEquals(
            "MENU means the carousel from any page and under any interruption",
            BodyType.MENU.value,
            frame.bodyType
        )
        assertTrue(
            "And the carousel opens on its first entry",
            frame.payload.containsAscii("Weather")
        )
    }

    /**
     * The path that already worked, kept so the fix does not buy one hole with another:
     * `withdrawInterrupt` puts the root up when the call ends with nothing underneath.
     */
    @Test
    fun `withdrawing the interrupt still puts the root up`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.ringWhileTheClusterKeepsTheScreen()
        runCurrent()

        harness.callDriver.remove()
        runCurrent()

        val frame = harness.btu.baseFrames().last()
        assertEquals(
            "A call that hangs up leaves the rider on the carousel, not on a dead screen",
            BodyType.MENU.value,
            frame.bodyType
        )
        assertEquals(
            "And with exactly one page on the stack",
            listOf("RootMenu"),
            harness.engine.stack.snapshot().map { it.name }
        )
    }

}
