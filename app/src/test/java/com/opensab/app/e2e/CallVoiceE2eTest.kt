package com.opensab.app.e2e

import com.opensab.domain.audio.Cue
import com.opensab.domain.contacts.CallMenuGates
import com.opensab.domain.contacts.Contact
import com.opensab.domain.contacts.PhoneKind
import com.opensab.domain.contacts.PhoneNumber
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.session.projection.RenderState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole voice dialing chain over the simulated cable and the graph Hilt builds:
 * RootMenu (carousel) -> navigate to Phone -> SELECT -> CallMenu (two-row menu) ->
 * SELECT on "By voice" -> CallDialPage (Listening, beep emitted, "llama a Ana", contact directory finds "Ana Ruiz") ->
 * CallDialPage (Confirming, cluster shows "Ana Ruiz" and "Mobile") ->
 * SELECT -> CallDialPage places the call via CallDialer and keeps "Calling..." until the dialler
 * reports it -> replaced on stack by live CallPage.
 *
 * `runCurrent()` and `advanceTimeBy()` throughout, never `advanceUntilIdle()`: it does not run
 * `backgroundScope` work and leaves the virtual clock at 0.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallVoiceE2eTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val ana = Contact(
        id = 1L,
        displayName = "Ana Ruiz",
        starred = false,
        numbers = listOf(PhoneNumber("600100100", PhoneKind.MOBILE))
    )

    @Test
    fun `should dial by voice from the carousel to the live call`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.callMenuAccess.next = CallMenuGates(voice = true, favorites = emptyList())
        harness.recognizer.willHear("llama a Ana")
        harness.contactDirectory.results = listOf(ana)

        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()

        repeat(2) {
            harness.press(FunctionId.NEXT)
            runCurrent()
        }

        harness.press(FunctionId.SELECT)
        runCurrent()
        assertEquals("CallMenu", harness.mountedPageName())

        harness.press(FunctionId.SELECT)
        runCurrent()

        assertTrue("the LISTEN cue announces the turn", harness.cuePlayer.played.contains(Cue.LISTEN))
        assertEquals("Ana Ruiz", harness.lastLiveScreen().contentTextLine1)
        assertEquals("Mobile", harness.lastLiveScreen().contentTextLine2)
        assertTrue(harness.btu.baseFrames().last().payload.containsAscii("Ana Ruiz"))

        harness.press(FunctionId.SELECT)
        runCurrent()

        assertEquals(listOf("600100100"), harness.callDialer.placedNumbers)
        assertEquals("still calling until the dialler reports the call", "CallDialPage", harness.mountedPageName())

        // Google Dialer's ongoing-call notification, callType 2, as on the 2026-09-30 ride.
        harness.callDriver.post(callerName = "Ana Ruiz", callerNumber = "600100100", callType = 2)
        runCurrent()

        assertEquals("CallPage", harness.mountedPageName())
        assertTrue(harness.btu.baseFrames().none { it.payload.containsAscii("No Call") })
    }
}

private suspend fun SessionHarness.press(functionId: FunctionId) = btu.press(functionId)

private fun SessionHarness.mountedPageName(): String? = engine.stack.peek()?.name

private fun SessionHarness.lastLiveScreen(): ProjectionFrame.LiveScreen {
    val state = screenProjector.renderState.value as? RenderState.Projecting
        ?: error("Expected Projecting state, got ${screenProjector.renderState.value}")
    return state.baseFrame as? ProjectionFrame.LiveScreen
        ?: error("Expected LiveScreen baseFrame, got ${state.baseFrame}")
}
