package com.opensab.app.engine.indicators

import com.opensab.app.di.MessageModule
import com.opensab.app.di.NotificationModule
import com.opensab.cluster.indicators.MessageIndicator
import com.opensab.domain.message.MessageNotification
import com.opensab.domain.message.MessageNotificationEvent
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.RecordingScreenProjector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MESSAGE bit, lit on the cluster side from the message source's state, which does not own it.
 *
 * MESSAGE stays lit while a message is unread, which outlives the page: the page yields with
 * `PageArgs(ActiveIndicators.Type.MESSAGE, true)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageIndicatorTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val projector = RecordingScreenProjector()
    private val publisher = ActiveIndicatorsPublisher(projector)
    private val bus = NotificationModule.provideMessageNotificationBus()
    private val messages = MessageModule.provideMessageSource(bus)

    private fun whatsApp(key: String = KEY) = MessageNotificationEvent.Posted(
        MessageNotification(
            packageName = "com.whatsapp",
            key = key,
            title = "Elena",
            text = "Are you far?",
            conversationTitle = null,
            isGroupConversation = false,
            isGroupSummary = false,
            isOngoing = false,
            postTimeMillis = 1_700_000_000_000L
        )
    )

    @Test
    fun `a posted message lights the bit`() = testScope.runTest {
        messages.start(backgroundScope)
        MessageIndicator(messages.currentMessage, publisher).start(backgroundScope)
        runCurrent()

        bus.post(whatsApp())
        runCurrent()

        assertTrue("MESSAGE must be lit", ActiveIndicator.MESSAGE in publisher.state.value)
        assertEquals(listOf(ActiveIndicator.MESSAGE.bit), projector.indicatorMasks)
    }

    @Test
    fun `yielding the screen keeps the bit`() = testScope.runTest {
        messages.start(backgroundScope)
        MessageIndicator(messages.currentMessage, publisher).start(backgroundScope)
        runCurrent()
        bus.post(whatsApp())
        runCurrent()

        messages.yieldScreen()
        runCurrent()

        assertTrue("MESSAGE must stay lit after a yield", ActiveIndicator.MESSAGE in publisher.state.value)
        assertEquals(listOf(ActiveIndicator.MESSAGE.bit), projector.indicatorMasks)
    }

    @Test
    fun `retiring the notification puts the bit out`() = testScope.runTest {
        messages.start(backgroundScope)
        MessageIndicator(messages.currentMessage, publisher).start(backgroundScope)
        runCurrent()
        bus.post(whatsApp(key = "thread-1"))
        runCurrent()

        bus.post(MessageNotificationEvent.Removed("thread-1"))
        runCurrent()

        assertFalse(
            "MESSAGE must go dark once the rider deals with it",
            ActiveIndicator.MESSAGE in publisher.state.value
        )
        assertEquals(listOf(ActiveIndicator.MESSAGE.bit, 0x00), projector.indicatorMasks)
    }

    private companion object {
        const val KEY = "0|com.whatsapp|1|null|10123"
    }
}
