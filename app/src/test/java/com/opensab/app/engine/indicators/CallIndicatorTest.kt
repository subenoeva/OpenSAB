package com.opensab.app.engine.indicators

import com.opensab.app.di.NotificationModule
import com.opensab.app.di.OrchestratorModule
import com.opensab.cluster.indicators.CallIndicator
import com.opensab.domain.audio.NoAudioFocus
import com.opensab.domain.call.CallNotificationDriver
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.RecordingScreenProjector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The PHONE bit, lit on the cluster side from the call source's state, which does not own it. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallIndicatorTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val projector = RecordingScreenProjector()
    private val publisher = ActiveIndicatorsPublisher(projector)
    private val bus = NotificationModule.provideCallNotificationBus()
    private val calls = OrchestratorModule.provideDefaultCallManager(NoAudioFocus, bus)
    private val driver = CallNotificationDriver(bus)

    /**
     * PHONE is lit while telephony holds a live call. The engine reads the very same flow to decide
     * whether the call page holds the screen, so the bit and the page can never disagree.
     */
    @Test
    fun `the phone bit follows ringing and active only`() = testScope.runTest {
        calls.start(backgroundScope)
        CallIndicator(calls.activeCall, publisher).start(backgroundScope)
        runCurrent()

        driver.post(callerName = "Laura", callType = 1)
        runCurrent()
        driver.post(callerName = "Laura", callType = 2)
        runCurrent()
        driver.remove()
        runCurrent()

        assertEquals(
            "Lit once when it started ringing, kept while it was up, cleared when it went",
            listOf(ActiveIndicator.PHONE.bit, 0x00),
            projector.indicatorMasks
        )

        calls.stop()
    }

    /** Before telephony has said something, nothing at all reaches the cluster. */
    @Test
    fun `starting with no call writes nothing to the cluster`() = testScope.runTest {
        calls.start(backgroundScope)
        CallIndicator(calls.activeCall, publisher).start(backgroundScope)
        runCurrent()

        assertEquals(emptyList<Int>(), projector.indicatorMasks)
        assertEquals(emptyList<Any>(), projector.frames)

        calls.stop()
    }
}
