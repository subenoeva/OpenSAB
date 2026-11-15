package com.opensab.sab.session.ble

import java.util.UUID
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resumeWithException

/** One kind of GATT operation, typed by what its callback hands back. */
sealed class GattOp<T>(val label: String) {
    data object Connect : GattOp<Unit>("Connection")
    data object Discovery : GattOp<Unit>("Service discovery")
    data object Mtu : GattOp<Int>("MTU request")
    data object Read : GattOp<ByteArray>("Read")
    data object Write : GattOp<Unit>("Write")
    data object DescriptorWrite : GattOp<Unit>("CCCD write")
}

/**
 * The continuation each GATT operation leaves waiting for its callback, one slot per [GattOp].
 *
 * **Owner: the transport that creates it**, one per link. Its operation methods are the only callers
 * of [arm] and [disarm]; its callback bridge, running on the stack's binder thread, is the only caller
 * of [complete] and [failAll]; [reopen] belongs to its `connect()`. Every slot sits behind one lock. A slot is cleared inside the lock and
 * its continuation resumed outside it, so code resumed synchronously can never re-enter the lock.
 */
class PendingGattOperations {

    private val lock = Any()
    private class Slot(val continuation: CancellableContinuation<*>, val target: UUID?)

    private val slots = HashMap<GattOp<*>, Slot>()

    /** Set by [failAll]: the link is dead, so no callback will ever answer what is armed after it. */
    private var droppedBy: Throwable? = null

    /**
     * An occupant that is no longer active was cancelled by the watchdog before anything cleared its
     * slot, a legitimate race, so it is replaced without a word. An active one means two operations
     * of one kind are in flight, which [SerializedGattQueue] rules out: that is a broken invariant.
     *
     * [target] is the characteristic the operation is about, `null` for the link-wide ones.
     */
    fun <T> arm(op: GattOp<T>, continuation: CancellableContinuation<T>, target: UUID? = null) {
        synchronized(lock) {
            droppedBy?.let { throw BleDisconnectedException("${op.label} armed after the link dropped", it) }
            val occupant = slots[op]
            check(occupant == null || !occupant.continuation.isActive) { "${op.label} is already in flight" }
            slots[op] = Slot(continuation, target)
        }
    }

    /** The stack refused the submit: no callback will come. */
    fun disarm(op: GattOp<*>) {
        synchronized(lock) { slots.remove(op) }
    }

    /**
     * False when nobody is waiting any more, typically a callback arriving after the watchdog. A
     * callback about another characteristic than the armed one is the late answer of an operation
     * the watchdog already gave up on: it is dropped and the slot keeps waiting for its own.
     */
    fun <T> complete(op: GattOp<T>, result: Result<T>, target: UUID? = null): Boolean {
        val slot = synchronized(lock) {
            val armed = slots[op] ?: return false
            if (target != null && armed.target != null && armed.target != target) return false
            slots.remove(op)
        }
        @Suppress("UNCHECKED_CAST")
        val continuation = slot?.continuation as CancellableContinuation<T>?
        if (continuation == null || !continuation.isActive) return false
        continuation.resumeWith(result)
        return true
    }

    /** A new connection starts: operations are accepted again. */
    fun reopen() {
        synchronized(lock) { droppedBy = null }
    }

    fun failAll(cause: Throwable) {
        val waiting = synchronized(lock) {
            droppedBy = cause
            slots.values.map { it.continuation }.also { slots.clear() }
        }
        waiting.filter { it.isActive }.forEach { it.resumeWithException(cause) }
    }
}
