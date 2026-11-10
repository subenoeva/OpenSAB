package com.opensab.sab.session.ble

import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class PendingGattOperationsTest {

    private val pending = PendingGattOperations()

    @Test
    fun aCallbackResumesTheOperationWaitingForIt() = runTest(UnconfinedTestDispatcher()) {
        val read = async { suspendCancellableCoroutine { pending.arm(GattOp.Read, it) } }

        assertTrue(pending.complete(GattOp.Read, Result.success(byteArrayOf(0x01))))
        assertArrayEquals(byteArrayOf(0x01), read.await())
    }

    @Test
    fun aCallbackWithNobodyWaitingIsDropped() =
        assertFalse(pending.complete(GattOp.Write, Result.success(Unit)))

    @Test
    fun aLateCallbackAfterTheWatchdogCancelledIsDropped() = runTest(UnconfinedTestDispatcher()) {
        val read = async { suspendCancellableCoroutine { pending.arm(GattOp.Read, it) } }
        read.cancel()

        assertFalse(pending.complete(GattOp.Read, Result.success(byteArrayOf(0x01))))
    }

    @Test
    fun armingOverACancelledOccupantReplacesItSilently() = runTest(UnconfinedTestDispatcher()) {
        // The watchdog cancelled the first read but nothing cleared its slot yet.
        val stale = async { suspendCancellableCoroutine { pending.arm(GattOp.Read, it) } }
        stale.cancel()
        val fresh = async { suspendCancellableCoroutine { pending.arm(GattOp.Read, it) } }

        assertTrue(pending.complete(GattOp.Read, Result.success(byteArrayOf(0x02))))
        assertArrayEquals(byteArrayOf(0x02), fresh.await())
    }

    @Test
    fun armingOverAnActiveOccupantIsABrokenInvariant() = runTest(UnconfinedTestDispatcher()) {
        backgroundScope.async { suspendCancellableCoroutine { pending.arm(GattOp.Write, it) } }

        val second = async {
            runCatchingCancellable { suspendCancellableCoroutine<Unit> { pending.arm(GattOp.Write, it) } }
        }

        assertTrue(second.await().exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun aRefusedSubmitDisarmsTheSlot() = runTest(UnconfinedTestDispatcher()) {
        var continuation: CancellableContinuation<Int>? = null
        backgroundScope.async { suspendCancellableCoroutine { pending.arm(GattOp.Mtu, it); continuation = it } }

        pending.disarm(GattOp.Mtu)

        assertFalse(pending.complete(GattOp.Mtu, Result.success(247)))
        assertTrue(continuation!!.isActive)
    }

    @Test
    fun slotsAreIndependent() = runTest(UnconfinedTestDispatcher()) {
        val read = backgroundScope.async { suspendCancellableCoroutine { pending.arm(GattOp.Read, it) } }
        val mtu = async { suspendCancellableCoroutine { pending.arm(GattOp.Mtu, it) } }

        pending.complete(GattOp.Mtu, Result.success(247))

        assertTrue(read.isActive)
        assertEquals(247, mtu.await())
    }

    @Test
    fun aDisconnectFailsEveryPendingOperation() = runTest(UnconfinedTestDispatcher()) {
        val cause = BleDisconnectedException("Peer disconnected")
        val read = async { runCatchingCancellable { suspendCancellableCoroutine<ByteArray> { pending.arm(GattOp.Read, it) } } }
        val cccd = async { runCatchingCancellable { suspendCancellableCoroutine<Unit> { pending.arm(GattOp.DescriptorWrite, it) } } }

        pending.failAll(cause)

        assertEquals(cause.message, (read.await().exceptionOrNull() as BleDisconnectedException).message)
        assertEquals(cause.message, (cccd.await().exceptionOrNull() as BleDisconnectedException).message)
        assertFalse(pending.complete(GattOp.Read, Result.success(byteArrayOf())))
    }

    @Test
    fun anOperationArmedAfterTheLinkDroppedFailsAtOnce() = runTest(UnconfinedTestDispatcher()) {
        // The peer dropped while connect() was between steps: the MTU request armed next must not
        // sit out its watchdog waiting for a callback that a dead link will never send.
        pending.failAll(BleDisconnectedException("Peer disconnected"))

        val mtu = async { runCatchingCancellable { suspendCancellableCoroutine<Int> { pending.arm(GattOp.Mtu, it) } } }

        assertTrue(mtu.isCompleted)
        assertTrue(mtu.await().exceptionOrNull() is BleDisconnectedException)
    }

    @Test
    fun reopeningForTheNextConnectionAcceptsOperationsAgain() = runTest(UnconfinedTestDispatcher()) {
        pending.failAll(BleDisconnectedException("Peer disconnected"))
        pending.reopen()

        val read = async { suspendCancellableCoroutine { pending.arm(GattOp.Read, it) } }

        assertTrue(pending.complete(GattOp.Read, Result.success(byteArrayOf(0x04))))
        assertArrayEquals(byteArrayOf(0x04), read.await())
    }

    @Test
    fun theContinuationIsResumedOutsideTheLock() = runTest(UnconfinedTestDispatcher()) {
        // An unconfined resume runs the waiting code right inside complete(). If complete() still
        // held the lock, another thread arming a different slot from there would block.
        val otherThreadArmed = CountDownLatch(1)
        val read = async {
            val value = suspendCancellableCoroutine { pending.arm(GattOp.Read, it) }
            val other = thread {
                pending.disarm(GattOp.Write)
                otherThreadArmed.countDown()
            }
            if (!otherThreadArmed.await(2, TimeUnit.SECONDS)) {
                other.interrupt()
                fail("complete() resumed the continuation while holding the lock")
            }
            value
        }

        pending.complete(GattOp.Read, Result.success(byteArrayOf(0x03)))

        assertArrayEquals(byteArrayOf(0x03), read.await())
    }

    @Test
    fun eachOperationIsNamedForTheLog() =
        assertEquals("MTU request", GattOp.Mtu.label)
}
