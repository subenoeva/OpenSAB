package com.opensab.sab.session.ble

import com.opensab.sab.protocol.ble.SabGattUuids
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * Strictly serialized GATT operation queue.
 *
 * Ensures that only a single GATT operation - read, write, descriptor write, MTU request or
 * service discovery - is in flight at any moment, and enforces a watchdog timeout per operation
 * (default [SabGattUuids.OPERATION_TIMEOUT]). It is not write-only: naming it so once made every read timeout claim to be
 * a write, both in the message and in the top stack frame.
 */
class SerializedGattQueue(
    private val timeout: Duration = SabGattUuids.OPERATION_TIMEOUT
) {
    private val mutex = Mutex()

    /**
     * Executes a GATT operation under the serialized mutex with a watchdog timeout.
     *
     * [operation] is what a [BleTimeoutException] will name if the watchdog fires. Callers should
     * pass something that identifies the operation and its target, e.g. `"read of <uuid>"`;
     * the default exists only for call sites that have nothing better to say.
     */
    suspend fun <T> execute(
        operation: String = "GATT operation",
        block: suspend () -> T
    ): T {
        return mutex.withLock {
            try {
                withTimeout(timeout) {
                    block()
                }
            } catch (e: TimeoutCancellationException) {
                throw BleTimeoutException(operation, timeout)
            }
        }
    }
}
