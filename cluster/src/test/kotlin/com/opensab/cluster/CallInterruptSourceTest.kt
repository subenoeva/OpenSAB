package com.opensab.cluster
import com.opensab.cluster.call.CallPageProvider
import com.opensab.cluster.indicators.CallIndicator
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.CallNotificationBus
import com.opensab.domain.call.CallNotificationDriver
import com.opensab.domain.call.CallStatus
import com.opensab.domain.call.DefaultCallManager
import com.opensab.domain.call.RecordingTelecomAdapter
import com.opensab.domain.settings.InboundCallMode
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.RecordingScreenProjector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Do not take my screen", which is what [InboundCallMode.IGNORE] means.
 *
 * The mode belongs here and nowhere else. A call that does not interrupt still **is** a call: the
 * PHONE bit stays lit and the carousel entry stays open, because those belong to the status bar
 * translator (`CallIndicator`, which never reads the mode) and to the root menu, not to the
 * interrupt stack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallInterruptSourceTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val ringing = CallInfo(
        callerName = "Ana",
        callerNumber = "+34600000000",
        status = CallStatus.RINGING
    )

    @Test
    fun anIncomingCallDoesNotClaimTheScreenWhenModeIsIgnore() = testScope.runTest {
        val activeCall = MutableStateFlow<CallInfo?>(null)
        val source = CallInterruptSource(
            activeCall = activeCall,
            mode = MutableStateFlow(InboundCallMode.IGNORE)
        ) { CallPageProvider(activeCall, englishClusterText, RecordingTelecomAdapter()) }

        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { source.interrupting.collect { seen += it } }
        runCurrent()

        activeCall.value = ringing
        runCurrent()

        assertNull("mode is IGNORE: the call must not take the screen", seen.last())
        job.cancel()
    }

    /**
     * The half people forget. "Do not take my screen" is not "pretend there is no call": the rider
     * still has to see the handset on the status bar, and the bit is `CallIndicator`'s, lit from the
     * same call flow, not the interrupt stack's.
     */
    @Test
    fun theCallStillLightsThePhoneBitWhenModeIsIgnore() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val bus = CallNotificationBus()
        val callManager = DefaultCallManager(bus)
        callManager.start(backgroundScope)
        CallIndicator(callManager.activeCall, ActiveIndicatorsPublisher(projector)).start(backgroundScope)
        runCurrent()

        val source = CallInterruptSource(
            activeCall = callManager.activeCall,
            mode = MutableStateFlow(InboundCallMode.IGNORE)
        ) { CallPageProvider(callManager.activeCall, englishClusterText, callManager) }

        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { source.interrupting.collect { seen += it } }
        runCurrent()

        CallNotificationDriver(bus).post(callerName = "Ana", callerNumber = "+34600000000")
        runCurrent()

        assertNull("the screen stays the rider's", seen.last())
        assertEquals(
            "but the handset lights up all the same",
            listOf(ActiveIndicator.PHONE.bit),
            projector.indicatorMasks
        )

        job.cancel()
        callManager.stop()
    }

    @Test
    fun switchingFromIgnoreToAlertClaimsTheScreenForACallAlreadyRinging() = testScope.runTest {
        val activeCall = MutableStateFlow<CallInfo?>(null)
        val mode = MutableStateFlow(InboundCallMode.IGNORE)
        val source = CallInterruptSource(activeCall = activeCall, mode = mode) {
            CallPageProvider(activeCall, englishClusterText, RecordingTelecomAdapter())
        }

        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { source.interrupting.collect { seen += it } }
        runCurrent()

        activeCall.value = ringing
        runCurrent()
        assertNull(seen.last())

        mode.value = InboundCallMode.ALERT
        runCurrent()

        assertTrue(
            "the call is still ringing, so switching to ALERT has to hand the screen over",
            seen.last() is CallPageProvider
        )
        job.cancel()
    }

    /** With ALERT, nothing changed: this is the behaviour the whole engine already had. */
    @Test
    fun aCallTakesTheScreenWhenModeIsAlert() = testScope.runTest {
        val activeCall = MutableStateFlow<CallInfo?>(null)
        val source = CallInterruptSource(
            activeCall = activeCall,
            mode = MutableStateFlow(InboundCallMode.ALERT)
        ) { CallPageProvider(activeCall, englishClusterText, RecordingTelecomAdapter()) }

        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { source.interrupting.collect { seen += it } }
        runCurrent()

        activeCall.value = ringing
        runCurrent()

        assertTrue(seen.last() is CallPageProvider)
        job.cancel()
    }
}
