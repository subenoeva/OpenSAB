package com.opensab.sab.session

import com.opensab.logging.Log
import com.opensab.logging.LogSink
import com.opensab.sab.session.assignment.DefaultAssignmentManager
import com.opensab.sab.session.ble.BleDisconnectedException
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.MockGattTransport
import com.opensab.sab.session.input.DefaultInputDispatcher
import com.opensab.sab.session.projection.DefaultControlPointGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Field logs of 2026-09-24 to 2026-10-11: every session carried four warnings with a
 * `JobCancellationException`, one per notification subscription the session itself cancels on its
 * way through. Stopping a subscription is not trouble; the link dying under one is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSubscriptionLoggingTest {

    private data class Line(val priority: Int, val throwable: Throwable?, val message: String)

    private val lines = Collections.synchronizedList(mutableListOf<Line>())
    private val sink = LogSink { priority, t, message, args ->
        lines += Line(priority, t, if (args.isEmpty()) message.orEmpty() else String.format(message.orEmpty(), *args))
    }
    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Before
    fun installSink() = Log.install(sink)

    @After
    fun uninstallSink() = Log.uninstall(sink)

    private fun troubleAbout(subject: String): List<Line> =
        lines.filter { it.priority >= Log.WARN && it.message.startsWith(subject) }

    private fun cancellationsLogged(): List<Line> = lines.filter { it.throwable is CancellationException }

    @Test
    fun theAssignmentSubscriptionCancelledWhenTheSessionClosesIsNotAWarning() = testScope.runTest {
        val transport = MockGattTransport().apply { connect() }
        val session = CoroutineScope(coroutineContext + Job())

        DefaultAssignmentManager(resultTimeout = 1.milliseconds).executeAssignment(transport, session).getOrThrow()
        session.cancel()
        // The cancelled collector resumes on the unconfined event loop, which only turns once this
        // coroutine lets go.
        yield()

        assertEquals(emptyList<Line>(), troubleAbout("AssignmentControl notifications"))
        assertEquals(emptyList<Line>(), cancellationsLogged())
        assertTrue(lines.any { it.priority == Log.DEBUG && it.message == "AssignmentControl notifications subscription cancelled" })
    }

    @Test
    fun theControlPointSubscriptionStoppedAtCloseIsNotAWarning() = testScope.runTest {
        val gateway = DefaultControlPointGateway()
        gateway.start(MockGattTransport().apply { connect() }, backgroundScope)

        gateway.stop()
        runCurrent()

        assertEquals(emptyList<Line>(), troubleAbout("ControlPoint notifications"))
        assertEquals(emptyList<Line>(), cancellationsLogged())
        assertTrue(lines.any { it.priority == Log.DEBUG && it.message == "ControlPoint notifications subscription cancelled" })
    }

    @Test
    fun theHandlebarSubscriptionStoppedAtCloseIsNotAWarning() = testScope.runTest {
        val dispatcher = DefaultInputDispatcher(DefaultControlPointGateway())
        dispatcher.startListening(MockGattTransport().apply { connect() }, backgroundScope)

        dispatcher.stopListening()
        runCurrent()

        assertEquals(emptyList<Line>(), troubleAbout("FunctionNotification observation"))
        assertEquals(emptyList<Line>(), cancellationsLogged())
        assertTrue(lines.any { it.priority == Log.DEBUG && it.message == "FunctionNotification observation cancelled" })
    }

    @Test
    fun aSubscriptionTheLinkKillsIsStillAWarningWithItsCause() = testScope.runTest {
        val linkLost = BleDisconnectedException("link lost under the subscription")
        val transport = DyingSubscriptions(MockGattTransport().apply { connect() }, linkLost)

        val gateway = DefaultControlPointGateway()
        gateway.start(transport, backgroundScope)
        DefaultInputDispatcher(gateway).startListening(transport, backgroundScope)
        DefaultAssignmentManager(resultTimeout = 1.milliseconds).executeAssignment(transport, backgroundScope)
        runCurrent()

        assertEquals(
            listOf(
                "ControlPoint notifications subscription terminated with error",
                "FunctionNotification observation failed",
                "AssignmentControl notifications subscription terminated with error"
            ),
            lines.filter { it.priority == Log.WARN && it.throwable === linkLost }.map { it.message }.distinct()
        )
    }

    /** Arms every subscription, then fails it the way a dropped link does. */
    private class DyingSubscriptions(
        private val delegate: GattTransport,
        private val failure: Throwable
    ) : GattTransport by delegate {
        override fun observeNotifications(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            onSubscriptionArmed: (suspend () -> Unit)?
        ): Flow<ByteArray> = flow {
            onSubscriptionArmed?.invoke()
            throw failure
        }
    }
}
