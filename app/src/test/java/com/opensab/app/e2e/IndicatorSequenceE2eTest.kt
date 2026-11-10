package com.opensab.app.e2e

import com.opensab.domain.message.MessageNotification
import com.opensab.domain.message.MessageNotificationEvent
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.nav.ManeuverType
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.settings.InboundCallMode
import com.opensab.domain.settings.InboundMessageMode
import com.opensab.sab.protocol.projection.ActiveIndicator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the whole mask sequence a session writes to the cluster. Written with the standard `runTest`
 * dispatcher — not `UnconfinedTestDispatcher`, which the harness's other e2e tests use — because
 * what matters here is what the standard dispatcher does and does not run before a session closes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IndicatorSequenceE2eTest {

    private fun whatsApp() = MessageNotificationEvent.Posted(
        MessageNotification(
            packageName = "com.whatsapp",
            key = "0|com.whatsapp|1|null|10123",
            title = "Elena",
            text = "Are you far",
            conversationTitle = null,
            isGroupConversation = false,
            isGroupSummary = false,
            isOngoing = false,
            postTimeMillis = 1_700_000_000_000L
        )
    )

    private fun instruction() = NavInstruction(
        title = "In 200 m",
        streetName = "Gran Via",
        distanceFormatted = "200 m",
        maneuver = ManeuverType.TURN_RIGHT
    )

    @Test
    fun `a session lights and clears every source bit in the order it does today`() = runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()

        harness.media.setStatus(PlaybackStatus.PLAYING)
        runCurrent()
        harness.callDriver.post(callerName = "Ana", callerNumber = "+34600000000")
        runCurrent()
        harness.messageBus.post(whatsApp())
        runCurrent()
        harness.navigationBus.post(instruction())
        runCurrent()

        harness.callDriver.remove()
        runCurrent()
        harness.messageBus.post(MessageNotificationEvent.Removed("0|com.whatsapp|1|null|10123"))
        runCurrent()
        harness.navigationBus.post(null)
        runCurrent()
        harness.media.setStatus(PlaybackStatus.PAUSED)
        runCurrent()

        assertEquals(
            "the whole mask sequence a live session writes to the cluster today",
            listOf(
                ActiveIndicator.MUSIC.bit,
                ActiveIndicator.MUSIC.bit or ActiveIndicator.PHONE.bit,
                ActiveIndicator.MUSIC.bit or ActiveIndicator.PHONE.bit or ActiveIndicator.MESSAGE.bit,
                ActiveIndicator.MUSIC.bit or ActiveIndicator.PHONE.bit or ActiveIndicator.MESSAGE.bit or ActiveIndicator.NAVIGATION.bit,
                ActiveIndicator.MUSIC.bit or ActiveIndicator.MESSAGE.bit or ActiveIndicator.NAVIGATION.bit,
                ActiveIndicator.MUSIC.bit or ActiveIndicator.NAVIGATION.bit,
                ActiveIndicator.MUSIC.bit,
                0x00
            ),
            harness.btu.indicatorMasks
        )
    }

    @Test
    fun `closing a session writes the indicator frames it writes today`() = runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()

        harness.media.setStatus(PlaybackStatus.PLAYING)
        runCurrent()
        harness.callDriver.post(callerName = "Ana", callerNumber = "+34600000000")
        runCurrent()
        harness.messageBus.post(whatsApp())
        runCurrent()
        harness.navigationBus.post(instruction())
        runCurrent()
        val masksWhileAlive = harness.btu.indicatorMasks.size

        harness.disconnect()
        runCurrent()

        assertEquals(
            "no more mask writes reach the cluster once the session is closing",
            emptyList<Int>(),
            harness.btu.indicatorMasks.drop(masksWhileAlive)
        )
    }

    @Test
    fun `ignoring calls still lights PHONE and leaves the screen alone`() = runTest {
        val harness = SessionHarness(backgroundScope)
        harness.settings.set { it.copy(inboundCallMode = InboundCallMode.IGNORE) }
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()

        harness.callDriver.post(callerName = "Ana", callerNumber = "+34600000000")
        runCurrent()

        assertTrue(
            "ignoring the call must not stop it lighting the bit",
            ActiveIndicator.PHONE in harness.indicators.state.value
        )
        assertEquals(
            "and what the cluster was last told has to be what the publisher believes it left",
            harness.indicators.mask,
            harness.btu.indicatorMasks.last()
        )
        assertEquals(
            "ignoring a call must not take the screen",
            "RootMenu",
            harness.engine.stack.peek()?.name
        )
    }

    @Test
    fun `ignoring messages still lights MESSAGE and leaves the screen alone`() = runTest {
        val harness = SessionHarness(backgroundScope)
        harness.settings.set { it.copy(inboundMessageMode = InboundMessageMode.IGNORE) }
        harness.connect().getOrThrow()
        harness.handOverScreen()
        runCurrent()

        harness.messageBus.post(whatsApp())
        runCurrent()

        assertTrue(
            "ignoring the message must not stop it lighting the bit",
            ActiveIndicator.MESSAGE in harness.indicators.state.value
        )
        assertEquals(
            "and what the cluster was last told has to be what the publisher believes it left",
            harness.indicators.mask,
            harness.btu.indicatorMasks.last()
        )
        assertEquals(
            "ignoring a message must not take the screen",
            "RootMenu",
            harness.engine.stack.peek()?.name
        )
    }
}
