package com.opensab.domain.call

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one behaviour that separates this bus from its two siblings: a late collector is handed the
 * call notifications that are live, and nothing else.
 *
 * The edge announces the end of a call **by key** for every notification that is not a live call
 * — Google Dialer ends a rejected one by re-posting the same key without its `CallStyle`, and
 * nothing is ever retired — and a phone posts notifications all day. What the bus keeps is which
 * keys are live, so neither that noise nor the passing of time can change what a late collector
 * rebuilds.
 *
 * A fresh [CallNotificationBus] per test: the bus is an owned instance and not a global, so there
 * is nothing to reset between tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallNotificationBusTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun ringing(key: String = DIALER_KEY, callType: Int = 1) = CallNotificationEvent.Posted(
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

    /**
     * Connecting the motorcycle mid-call has to light the PHONE bit and mount the page.
     *
     * The source only starts when the session does, so a call that was
     * already up when the rider connected published its event before anybody was listening. A
     * message is an event and replaying it would read the rider something that already happened; a
     * live call is a state, and if it is still alive the cluster has to find out.
     */
    @Test
    fun aLateCollectorFindsTheCallThatIsStillLive() = runTest {
        val bus = CallNotificationBus()
        val notification = CallNotification(
            key = "0|com.google.android.dialer|1|null|10137",
            packageName = "com.google.android.dialer",
            category = "call",
            template = "android.app.Notification\$CallStyle",
            callType = 2,
            title = "Laura",
            personName = null,
            personUri = null
        )

        bus.post(
            CallNotificationEvent.Posted(notification, NoCallCommands)
        )

        // Nobody was collecting when it was posted, which is the whole point.
        val replayed = bus.events.first()

        assertEquals(
            notification,
            (replayed as CallNotificationEvent.Posted).notification
        )
    }

    /**
     * The same case with the noise of a phone behind it, which is what the announcement by key
     * turned it into: with one event of memory the message that arrived while the phone rang would
     * push the call out of the cache, and the motorcycle connecting right then would find nothing.
     */
    @Test
    fun theCallSurvivesTheNotificationsPostedAfterIt() = testScope.runTest {
        val bus = CallNotificationBus()
        bus.post(ringing())
        repeat(12) { bus.post(CallNotificationEvent.Removed("$OTHER_KEY-$it")) }

        assertEquals(
            listOf(DIALER_KEY),
            replayed(bus).filterIsInstance<CallNotificationEvent.Posted>().map { it.notification.key }
        )
    }

    /**
     * The field run of 2026-09-25, literally: the only call of the day rang, was answered and ended
     * at 11:26, and a ride that started an hour later collected the bus and was handed that dead
     * call again — ringing, gone, active, gone — lighting PHONE and taking the audio focus for a
     * call nobody was on. A call that ended is not state, and a late collector must not see it.
     */
    @Test
    fun aCallThatEndedBeforeTheCollectorArrivedIsNotReplayed() = testScope.runTest {
        val bus = CallNotificationBus()
        bus.post(ringing(callType = 1))
        bus.post(ringing(callType = 1))
        bus.post(CallNotificationEvent.Removed(DIALER_KEY))
        bus.post(ringing(callType = 2))
        bus.post(ringing(callType = 2))
        bus.post(CallNotificationEvent.Removed(DIALER_KEY))
        repeat(5) { bus.post(CallNotificationEvent.Removed("$OTHER_KEY-$it")) }

        assertEquals(emptyList<CallNotificationEvent>(), replayed(bus))
    }

    /**
     * The other half of the same disease: a window of events buries a live call under the
     * notifications posted after it, because the edge posts a `Removed` for every one that is not a
     * call. The state has no window, so a hundred of them change nothing.
     */
    @Test
    fun aLiveCallSurvivesAHundredNotificationsPostedAfterIt() = testScope.runTest {
        val bus = CallNotificationBus()
        bus.post(ringing())
        repeat(100) { bus.post(CallNotificationEvent.Removed("$OTHER_KEY-$it")) }

        assertEquals(listOf(ringing()), replayed(bus))
    }

    /** A call that was answered is handed over as it is now, not as the history of how it got there. */
    @Test
    fun aLateCollectorGetsOnlyTheNewestPostOfEachLiveKey() = testScope.runTest {
        val bus = CallNotificationBus()
        bus.post(ringing(callType = 1))
        bus.post(ringing(callType = 2))
        bus.post(ringing(key = SECOND_CALL_KEY))

        assertEquals(listOf(ringing(callType = 2), ringing(key = SECOND_CALL_KEY)), replayed(bus))
    }

    /** Without the listener nothing about the shade is known, so nothing is live any more. */
    @Test
    fun listenerDisconnectedForgetsEveryLiveCall() = testScope.runTest {
        val bus = CallNotificationBus()
        bus.post(ringing())
        bus.post(ringing(key = SECOND_CALL_KEY))
        bus.post(CallNotificationEvent.ListenerDisconnected)

        assertEquals(emptyList<CallNotificationEvent>(), replayed(bus))
    }

    /** A collector already listening is handed the live state first and then every event, in order. */
    @Test
    fun eventsPostedAfterTheSubscriptionArriveInOrder() = testScope.runTest {
        val bus = CallNotificationBus()
        bus.post(ringing(key = SECOND_CALL_KEY))
        val seen = mutableListOf<CallNotificationEvent>()
        backgroundScope.launch { bus.events.toList(seen) }
        runCurrent()

        val live = listOf(
            ringing(callType = 1),
            CallNotificationEvent.Removed(OTHER_KEY),
            ringing(callType = 2),
            CallNotificationEvent.Removed(DIALER_KEY),
            CallNotificationEvent.ListenerDisconnected
        )
        live.forEach { bus.post(it) }
        runCurrent()

        assertEquals(listOf(ringing(key = SECOND_CALL_KEY)) + live, seen)
    }

    /**
     * A collector that has subscribed but not run yet — the session thread busy painting while the
     * phone posts — must still get every event once it runs. The edge posts a `Removed` for every
     * notification that is not a call, so a slow collector can fall far behind, and dropping the
     * dialer's `Removed` among them would pin a dead call for the whole session.
     */
    @Test
    fun aCollectorThatFellFarBehindStillGetsTheEndOfTheCall() = runTest {
        val bus = CallNotificationBus()
        val seen = mutableListOf<CallNotificationEvent>()
        backgroundScope.launch { bus.events.toList(seen) }
        runCurrent()

        bus.post(ringing())
        repeat(100) { bus.post(CallNotificationEvent.Removed("$OTHER_KEY-$it")) }
        bus.post(CallNotificationEvent.Removed(DIALER_KEY))
        runCurrent()

        assertEquals(102, seen.size)
        assertEquals(CallNotificationEvent.Removed(DIALER_KEY), seen.last())
    }

    /**
     * The bus KDoc's claim that an instance is a pipe and not a shared truth: two buses
     * never see each other's traffic. A late collector on one instance must not find what was posted
     * on another — the exact leak a global would have let through between two owners of the graph.
     */
    @Test
    fun twoBusesShareNoReplay() = testScope.runTest {
        val first = CallNotificationBus()
        val second = CallNotificationBus()

        first.post(ringing())

        assertEquals(
            "a late collector on the second bus must not see what the first replayed",
            emptyList<CallNotificationEvent>(),
            replayed(second)
        )
    }

    private fun TestScope.replayed(bus: CallNotificationBus): List<CallNotificationEvent> {
        val seen = mutableListOf<CallNotificationEvent>()
        val job = backgroundScope.launch { bus.events.toList(seen) }
        runCurrent()
        job.cancel()
        return seen
    }

    private object NoCallCommands : CallCommands {
        override fun answer() = Unit
        override fun reject() = Unit
        override fun disconnect() = Unit
    }

    private companion object {
        const val DIALER_KEY = "0|com.google.android.dialer|1|null|10137"
        const val OTHER_KEY = "0|com.whatsapp|1|null|10123"
        const val SECOND_CALL_KEY = "0|com.whatsapp|1|call|10124"
    }
}
