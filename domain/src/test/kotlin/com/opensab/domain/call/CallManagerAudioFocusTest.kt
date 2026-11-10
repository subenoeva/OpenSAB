package com.opensab.domain.call

import com.opensab.domain.audio.AudioFocusKind
import com.opensab.domain.audio.RecordingAudioFocus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A live call pauses the music, and the manager that owns the call owns that too.
 *
 * `AUDIOFOCUS_GAIN_TRANSIENT` during a call — not `MAY_DUCK`, which
 * is the voice's mode: a conversation the rider has to hold is not something to talk over.
 *
 * The owner is [DefaultCallManager] and not the call page, because the focus has the span of the
 * **call** and not of the screen: the page is dismissed while the call goes on, and the call ends
 * while the rider is looking at something else. That span is already owned here — it is the span of
 * `activeCall`, the same one the cluster lights the PHONE bit for — so the focus lives next to the
 * call rather than acquiring a second owner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallManagerAudioFocusTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val focus = RecordingAudioFocus()

    /** A fresh instance per test: the bus is owned, not a global. */
    private val bus = CallNotificationBus()

    private fun manager() = DefaultCallManager(
        notifications = bus,
        audioFocus = focus,
        nowMillis = { FIRST_STAMP }
    )

    private fun ringing(key: String = DIALER_KEY, callType: Int = 1) =
        CallNotificationEvent.Posted(
            notification = CallNotification(
                key = key,
                packageName = "com.google.android.dialer",
                category = "call",
                template = "android.app.Notification\$CallStyle",
                callType = callType,
                title = "Laura",
                personName = null,
                personUri = null
            ),
            commands = NoCallCommands
        )

    @Test
    fun aLiveCallPausesTheMusic() = testScope.runTest {
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing())
        runCurrent()

        assertEquals(listOf(AudioFocusKind.TRANSIENT), focus.requests)
        assertEquals(listOf(AudioFocusKind.TRANSIENT), focus.held)

        callManager.stop()
    }

    @Test
    fun theMusicComesBackWhenTheCallGoesAway() = testScope.runTest {
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing())
        runCurrent()
        bus.post(CallNotificationEvent.Removed(DIALER_KEY))
        runCurrent()

        assertEquals(listOf(AudioFocusKind.TRANSIENT), focus.abandoned)
        assertTrue("the music stayed paused after the call ended", focus.held.isEmpty())

        callManager.stop()
    }

    /**
     * The dialer reposts the same call on every state change — RINGING to ACTIVE and every repost
     * in between — and one field pass had thirteen of them for one call. Taking the focus
     * again on each would make the music stutter through a whole conversation.
     */
    @Test
    fun answeringTheCallDoesNotTakeTheFocusTwice() = testScope.runTest {
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing(callType = 1))
        runCurrent()
        bus.post(ringing(callType = 2))
        runCurrent()

        assertEquals(listOf(AudioFocusKind.TRANSIENT), focus.requests)
        assertTrue("the focus was given back mid-call", focus.abandoned.isEmpty())

        callManager.stop()
    }

    /**
     * The listener going away drops the call, and it has to drop the focus with it: a manager that
     * has forgotten the call can never be asked to give the music back.
     */
    @Test
    fun losingTheListenerGivesTheMusicBack() = testScope.runTest {
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing())
        runCurrent()
        bus.post(CallNotificationEvent.ListenerDisconnected)
        runCurrent()

        assertTrue("the music stayed paused with no call left", focus.held.isEmpty())

        callManager.stop()
    }

    @Test
    fun stoppingTheManagerGivesTheMusicBack() = testScope.runTest {
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing())
        runCurrent()
        callManager.stop()
        runCurrent()

        assertTrue("stopping left the music paused", focus.held.isEmpty())
    }

    private object NoCallCommands : CallCommands {
        override fun answer() = Unit
        override fun reject() = Unit
        override fun disconnect() = Unit
    }

    private companion object {
        const val DIALER_KEY = "0|com.google.android.dialer|1|null|10137"
        const val FIRST_STAMP = 1_700_000_000_000L
    }
}
