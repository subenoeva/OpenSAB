package com.opensab.sab.session.ble

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Fans the stack's notifications out by characteristic.
 *
 * **Owner: the transport that creates it**, one per link. Its callback bridge is the only caller of
 * [publish]; everything else only reads through [stream]. The buffer is shared by every
 * characteristic and a subscriber that falls more than [capacity] behind loses the oldest.
 */
class NotificationRouter(private val capacity: Int = 64) {

    private class Event(val characteristic: UUID, val payload: ByteArray)

    private val bus = MutableSharedFlow<Event>(
        extraBufferCapacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** False only if the bus refused the event, which [BufferOverflow.DROP_OLDEST] never does. */
    fun publish(characteristic: UUID, payload: ByteArray): Boolean = bus.tryEmit(Event(characteristic, payload))

    fun stream(characteristic: UUID): Flow<ByteArray> = bus
        .filter { it.characteristic == characteristic }
        .map { it.payload }
}
