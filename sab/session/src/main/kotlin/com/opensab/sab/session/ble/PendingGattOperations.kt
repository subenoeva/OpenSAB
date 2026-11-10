package com.opensab.sab.session.ble

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
    private val slots = HashMap<GattOp<*>, CancellableContinuation<*>>()

    /** Set by [failAll]: the link is dead, so no callback will ever answer what is armed after it. */
    private var droppedBy: Throwable? = null

    /**
     * An occupant that is no longer active was cancelled by the watchdog before anything cleared its
     * slot, a legitimate race, so it is replaced without a word. An active one means two operations
     * of one kind are in flight, which [SerializedGattQueue] rules out: that is a broken invariant.
     */
    fun <T> arm(op: GattOp<T>, continuation: CancellableContinuation<T>) {
        synchronized(lock) {
            droppedBy?.let { throw BleDisconnectedException("${op.label} armed after the link dropped", it) }
            val occupant = slots[op]
            check(occupant == null || !occupant.isActive) { "${op.label} is already in flight" }
            slots[op] = continuation
        }
    }

    /** The stack refused the submit: no callback will come. */
    fun disarm(op: GattOp<*>) {
        synchronized(lock) { slots.remove(op) }
    }

    /** False when nobody is waiting any more, typically a callback arriving after the watchdog. */
    fun <T> complete(op: GattOp<T>, result: Result<T>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val continuation = synchronized(lock) { slots.remove(op) } as CancellableContinuation<T>?
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
            slots.values.toList().also { slots.clear() }
        }
        waiting.filter { it.isActive }.forEach { it.resumeWithException(cause) }
    }
}
