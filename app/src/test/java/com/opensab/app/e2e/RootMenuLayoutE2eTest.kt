package com.opensab.app.e2e

import com.opensab.domain.settings.RootMenuEntry
import com.opensab.domain.settings.RootMenuRow
import com.opensab.domain.settings.withRootMenu
import com.opensab.sab.protocol.projection.BodyType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A root-menu settings write reaches the wire as the next MENU frame. */
@OptIn(ExperimentalCoroutinesApi::class)
class RootMenuLayoutE2eTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun layout(vararg visible: RootMenuEntry) =
        (visible.map { RootMenuRow(it, true) } +
            (RootMenuEntry.entries - visible.toSet()).map { RootMenuRow(it, false) })

    @Test
    fun `reordering in the settings repaints the carousel mid session`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()

        harness.settings.updateSettings { it.withRootMenu(layout(RootMenuEntry.MUSIC, RootMenuEntry.CALL)) }
        runCurrent()

        val frame = harness.btu.baseFrames().last()
        assertEquals(BodyType.MENU.value, frame.bodyType)
        assertTrue("Music is first now, so it is what the header says", frame.payload.containsAscii("Music"))
    }

    @Test
    fun `the layout stored before the session is the one the cluster opens on`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.settings.updateSettings { it.withRootMenu(layout(RootMenuEntry.CALL)) }
        harness.connect().getOrThrow()

        harness.handOverScreen()

        assertTrue(harness.btu.baseFrames().last().payload.containsAscii("Phone"))
    }

    /** Hiding an entry is not switching the function off. */
    @Test
    fun `a hidden call entry still lets a ringing call through`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.settings.updateSettings { it.withRootMenu(layout(RootMenuEntry.MUSIC)) }
        harness.connect().getOrThrow()
        harness.handOverScreen()

        harness.callDriver.post(callerName = "Ana", callerNumber = "+34600000000")
        runCurrent()

        assertEquals(listOf("RootMenu", "CallPage"), harness.engine.stack.snapshot().map { it.name })
    }
}
