package com.opensab.cluster
import com.opensab.cluster.call.CallPageProvider
import com.opensab.cluster.message.MessagePageProvider
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.CallStatus
import com.opensab.domain.call.RecordingTelecomAdapter
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.message.MockMessageSource
import com.opensab.domain.settings.InboundCallMode
import com.opensab.domain.settings.InboundMessageMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who wins when telephony and messaging both want the screen.
 *
 * `BaseStateManager` mounts one interrupt at a time, so with two sources able to claim it somebody
 * has to say who outranks whom. A ringing call outranks an arriving message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PriorityInterruptSourceTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private class Sources {
        val activeCall = MutableStateFlow<CallInfo?>(null)
        val message = MockMessageSource()

        val callPage = CallPageProvider(activeCall, englishClusterText, RecordingTelecomAdapter())
        val messagePage = MessagePageProvider(message.currentMessage, englishClusterText, message)

        val interrupts = PriorityInterruptSource(
            CallInterruptSource(activeCall, MutableStateFlow(InboundCallMode.ALERT)) { callPage },
            MessageInterruptSource(
                claimingScreen = message.claimingScreen,
                mode = MutableStateFlow(InboundMessageMode.READ_DIRECTLY),
                promptPageFactory = { messagePage },
                readPageFactory = { messagePage }
            )
        )

        fun ring() {
            activeCall.value = CallInfo(
                callerName = "Ana",
                callerNumber = "+34600000000",
                status = CallStatus.RINGING
            )
        }

        fun hangUp() {
            activeCall.value = null
        }

        fun arrive() {
            message.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))
        }
    }

    @Test
    fun claimsNothingWhileNobodyDoes() = testScope.runTest {
        val sources = Sources()
        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { sources.interrupts.interrupting.collect { seen += it } }

        assertNull("an idle session claims nothing", seen.last())
        job.cancel()
    }

    @Test
    fun aMessageAloneTakesTheScreen() = testScope.runTest {
        val sources = Sources()
        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { sources.interrupts.interrupting.collect { seen += it } }

        sources.arrive()

        assertTrue("the message page must claim the screen", seen.last() is MessagePageProvider)
        job.cancel()
    }

    @Test
    fun aRingingCallOutranksAnArrivingMessage() = testScope.runTest {
        val sources = Sources()
        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { sources.interrupts.interrupting.collect { seen += it } }

        sources.arrive()
        sources.ring()

        assertTrue("the call must win the screen", seen.last() is CallPageProvider)
        job.cancel()
    }

    /**
     * And when the call ends the message is still unread, so its claim surfaces on its own. That is
     * `combine` doing the arbitration, not a special case anybody had to write.
     */
    @Test
    fun theMessageSurfacesOnceTheCallEnds() = testScope.runTest {
        val sources = Sources()
        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { sources.interrupts.interrupting.collect { seen += it } }

        sources.arrive()
        sources.ring()
        sources.hangUp()

        assertTrue("the unread message must come back", seen.last() is MessagePageProvider)
        job.cancel()
    }

    /**
     * The case that makes `onStart { emit(null) }` load-bearing: `NoInterruptions` is an
     * `emptyFlow()`, and `combine` with a flow that never emits never emits either. Without the
     * prefix this composite would go silent for the whole session.
     */
    @Test
    fun stillEmitsWhenComposedWithASourceThatNeverEmits() = testScope.runTest {
        val sources = Sources()
        val composite = PriorityInterruptSource(
            NoInterruptions,
            MessageInterruptSource(
                claimingScreen = sources.message.claimingScreen,
                mode = MutableStateFlow(InboundMessageMode.READ_DIRECTLY),
                promptPageFactory = { sources.messagePage },
                readPageFactory = { sources.messagePage }
            )
        )
        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { composite.interrupting.collect { seen += it } }

        sources.arrive()

        assertTrue("a silent source must not mute the composite", seen.last() is MessagePageProvider)
        job.cancel()
    }

    /**
     * No `distinctUntilChanged`: re-emitting the same instance is how a mounted page says "redraw
     * me", and swallowing it would freeze a call on RINGING (`InterruptSource`).
     */
    @Test
    fun repeatsTheSameInstanceSoTheEngineCanRedraw() = testScope.runTest {
        val sources = Sources()
        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { sources.interrupts.interrupting.collect { seen += it } }

        sources.ring()
        sources.activeCall.value = CallInfo(
            callerName = "Ana",
            callerNumber = "+34600000000",
            status = CallStatus.ACTIVE
        )

        assertTrue(
            "the same call page must be offered twice so the engine invalidates",
            seen.filterIsInstance<CallPageProvider>().size >= 2
        )
        job.cancel()
    }
}
