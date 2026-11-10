package com.opensab.domain.call

import com.opensab.logging.Log
import com.opensab.logging.LogSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single owner of the live call.
 *
 * The platform edge translates and posts; this class decides nothing about what a call is —
 * [CallNotificationParser] does — and keeps everything about which one is live: its status, its
 * identity and its timestamp. The PHONE bit is lit on the cluster side from `activeCall`, and
 * tested there (`CallIndicatorTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    /** A fresh instance per test: the bus is owned, not a global. */
    private val bus = CallNotificationBus()

    private fun manager(nowMillis: () -> Long = { FIRST_STAMP }) = DefaultCallManager(
        notifications = bus,
        nowMillis = nowMillis
    )

    private fun ringing(
        key: String = DIALER_KEY,
        callType: Int = 1,
        title: String? = "Laura",
        commands: CallCommands = CountingCallCommands()
    ) = CallNotificationEvent.Posted(
        notification = CallNotification(
            key = key,
            packageName = "com.google.android.dialer",
            category = "call",
            template = "android.app.Notification\$CallStyle",
            callType = callType,
            title = title,
            personName = null,
            personUri = null
        ),
        commands = commands
    )

    @Test
    fun postingAnIncomingCallPublishesIt() = testScope.runTest {
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing())
        runCurrent()

        assertEquals("Laura", callManager.activeCall.value?.callerName)
        assertEquals(CallStatus.RINGING, callManager.activeCall.value?.status)
        assertEquals(FIRST_STAMP, callManager.activeCall.value?.timestamp)

        callManager.stop()
    }

    /**
     * The notification key does not move between RINGING and ACTIVE
     * (`0|com.google.android.dialer|1|null|10137` in both), so the
     * same call keeps its name, its number **and** its timestamp — which is what lets
     * `CallInterruptSource` re-emit one page instance for the whole call.
     */
    @Test
    fun theSameKeyWithANewStatusKeepsTheIdentity() = testScope.runTest {
        var now = FIRST_STAMP
        val callManager = manager { now }
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing(callType = 1))
        runCurrent()
        val whileRinging = callManager.activeCall.value

        now = SECOND_STAMP
        bus.post(ringing(callType = 2))
        runCurrent()
        val whileActive = callManager.activeCall.value

        assertEquals(CallStatus.RINGING, whileRinging?.status)
        assertEquals(CallStatus.ACTIVE, whileActive?.status)
        assertEquals(whileRinging?.callerName, whileActive?.callerName)
        assertEquals(whileRinging?.callerNumber, whileActive?.callerNumber)
        assertEquals(
            "The clock moved but the call did not: the timestamp is stamped once per key",
            FIRST_STAMP,
            whileActive?.timestamp
        )

        callManager.stop()
    }

    /**
     * The storm that never happens.
     *
     * A dialer reposts its call notification whenever its own UI refreshes. With the timestamp
     * stamped in the parser every repost would build a different `CallInfo`, the StateFlow would
     * emit, `CallInterruptSource` would re-emit and the engine would repaint — with the call sitting
     * perfectly still.
     */
    @Test
    fun fiftyIdenticalRepostsEmitOnce() = testScope.runTest {
        var now = FIRST_STAMP
        val callManager = manager { now }
        callManager.start(backgroundScope)
        runCurrent()

        val emissions = mutableListOf<CallInfo?>()
        backgroundScope.launch { callManager.activeCall.collect { emissions += it } }
        runCurrent()

        repeat(50) {
            bus.post(ringing())
            runCurrent()
            // The clock moves under the call precisely so that a stamped repost would show up.
            now += 1_000
        }

        assertEquals(
            "The initial null plus one call, and nothing else",
            listOf(null, CallInfo("Laura", "", CallStatus.RINGING, FIRST_STAMP)),
            emissions
        )

        callManager.stop()
    }

    @Test
    fun aNewKeyStartsANewCall() = testScope.runTest {
        var now = FIRST_STAMP
        val callManager = manager { now }
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing())
        runCurrent()
        val first = callManager.activeCall.value

        now = SECOND_STAMP
        bus.post(ringing(key = OTHER_KEY, title = "Marta"))
        runCurrent()
        val second = callManager.activeCall.value

        assertEquals("Marta", second?.callerName)
        assertEquals(SECOND_STAMP, second?.timestamp)
        assertNotSame(first, second)

        callManager.stop()
    }

    /**
     * The one way a call ends here, and it is deliberately not "the notification left the shade":
     * [CallNotificationEvent.Removed] means the key stopped being a live call, however it stopped.
     * Google Dialer answers a rejection by re-posting the very same key without its `CallStyle` and
     * retires nothing, and the edge announces that by key like any other ending. Read as "left the
     * shade", the cluster sat on a ringing call for six and a half minutes.
     */
    @Test
    fun removalOfTheCurrentKeyClearsTheCall() = testScope.runTest {
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing())
        runCurrent()

        bus.post(CallNotificationEvent.Removed(DIALER_KEY))
        runCurrent()

        assertNull(callManager.activeCall.value)

        callManager.stop()
    }

    @Test
    fun removalOfAnotherKeyLeavesTheCallAlone() = testScope.runTest {
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing())
        runCurrent()

        bus.post(CallNotificationEvent.Removed(OTHER_KEY))
        runCurrent()

        assertEquals("Laura", callManager.activeCall.value?.callerName)

        callManager.stop()
    }

    /** The dialer's missed-call notification travels the same bus and must move nothing. */
    @Test
    fun aNotificationTheParserRejectsDoesNotTouchTheState() = testScope.runTest {
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing())
        runCurrent()
        val live = callManager.activeCall.value

        bus.post(
            CallNotificationEvent.Posted(
                notification = CallNotification(
                    key = "0|com.google.android.dialer|1|MissedCall_content://call_log/calls/2|10137",
                    packageName = "com.google.android.dialer",
                    category = "missed_call",
                    template = null,
                    callType = null,
                    title = "Laura",
                    personName = null,
                    personUri = null
                ),
                commands = CountingCallCommands()
            )
        )
        runCurrent()

        assertSame(live, callManager.activeCall.value)

        callManager.stop()
    }

    @Test
    fun listenerDisconnectedClearsTheCall() = testScope.runTest {
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing())
        runCurrent()

        bus.post(CallNotificationEvent.ListenerDisconnected)
        runCurrent()

        assertNull(callManager.activeCall.value)

        callManager.stop()
    }

    @Test
    fun answerRoutesToTheCommandsThatCameWithTheCall() = testScope.runTest {
        val commands = CountingCallCommands()
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing(commands = commands))
        runCurrent()

        callManager.acceptCall()

        assertEquals(1, commands.answers)
        assertEquals(0, commands.rejects)
        assertEquals(0, commands.disconnects)

        callManager.stop()
    }

    @Test
    fun rejectAndDisconnectRouteToTheirOwnCommands() = testScope.runTest {
        val commands = CountingCallCommands()
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing(commands = commands))
        runCurrent()

        callManager.rejectCall()
        callManager.endCall()

        assertEquals(0, commands.answers)
        assertEquals(1, commands.rejects)
        assertEquals(1, commands.disconnects)

        callManager.stop()
    }

    /** Hanging up on the phone a moment before pressing SELECT on the handlebar. */
    @Test
    fun commandsAfterTheCallIsGoneDoNothing() = testScope.runTest {
        val commands = CountingCallCommands()
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing(commands = commands))
        runCurrent()
        bus.post(CallNotificationEvent.Removed(DIALER_KEY))
        runCurrent()

        callManager.acceptCall()
        callManager.rejectCall()
        callManager.endCall()

        assertEquals(0, commands.answers)
        assertEquals(0, commands.rejects)
        assertEquals(0, commands.disconnects)

        callManager.stop()
    }

    @Test
    fun eventsAfterStopDoNotMoveTheState() = testScope.runTest {
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        callManager.stop()
        bus.post(ringing())
        runCurrent()

        assertNull(callManager.activeCall.value)
    }

    /**
     * A ride that starts an hour after the only call of the day ended. The manager starts with the
     * session, long after that call's notifications were posted and retired, and must not rebuild
     * it: no `Call from` in the log, and [DefaultCallManager.activeCall] never leaves `null` — a
     * single non-null value lights PHONE, takes the audio focus and mounts the call page.
     */
    @Test
    fun startingAfterACallEndedNeverPublishesIt() = testScope.runTest {
        bus.post(ringing(callType = 1))
        bus.post(ringing(callType = 1))
        bus.post(CallNotificationEvent.Removed(DIALER_KEY))
        bus.post(ringing(callType = 2))
        bus.post(ringing(callType = 2))
        bus.post(CallNotificationEvent.Removed(DIALER_KEY))
        repeat(5) { bus.post(CallNotificationEvent.Removed("$OTHER_KEY-$it")) }

        val lines = mutableListOf<String>()
        val sink = LogSink { _, _, message, args ->
            lines += if (args.isEmpty()) message.orEmpty() else String.format(message.orEmpty(), *args)
        }
        Log.install(sink)
        try {
            val callManager = manager()
            val values = mutableListOf<CallInfo?>()
            backgroundScope.launch { callManager.activeCall.collect { values += it } }
            callManager.start(backgroundScope)
            runCurrent()

            // The log is the only observable of the flicker: `activeCall` is a StateFlow and
            // conflates a dead call set and cleared in one pass, so `values` stays `[null]` even
            // when the call was rebuilt. Do not drop the log check in favour of `values`.
            assertEquals(listOf<CallInfo?>(null), values)
            assertTrue(lines.none { it.startsWith("Call ") })

            callManager.stop()
        } finally {
            Log.uninstall(sink)
        }
    }

    /**
     * The session thread busy while the phone posts: the manager's collector has subscribed but does
     * not run until a hundred other notifications and the end of the call are behind it. The call
     * must still end, or PHONE, the page and the audio focus stay up for a call nobody is on.
     */
    @Test
    fun aCallThatEndsWhileTheCollectorIsFarBehindStillEnds() = runTest {
        val callManager = manager()
        callManager.start(backgroundScope)
        runCurrent()

        bus.post(ringing())
        repeat(100) { bus.post(CallNotificationEvent.Removed("$OTHER_KEY-$it")) }
        bus.post(CallNotificationEvent.Removed(DIALER_KEY))
        runCurrent()

        assertNull(callManager.activeCall.value)

        callManager.stop()
    }

    private class CountingCallCommands : CallCommands {
        var answers = 0
        var rejects = 0
        var disconnects = 0

        override fun answer() { answers++ }
        override fun reject() { rejects++ }
        override fun disconnect() { disconnects++ }
    }

    private companion object {
        const val DIALER_KEY = "0|com.google.android.dialer|1|null|10137"
        const val OTHER_KEY = "0|com.whatsapp|1|null|10222"
        const val FIRST_STAMP = 1_700_000_000_000L
        const val SECOND_STAMP = 1_700_000_030_000L
    }
}
