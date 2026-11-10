package com.opensab.sab.protocol.projection

import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe monotonic RequestId generator for correlating projection frames with Receipts.
 * Wraps around safely within 1..255.
 */
class RequestIdGenerator(initialValue: Int = 1) {

    private val counter = AtomicInteger(initialValue)

    fun nextId(): Int {
        return counter.getAndUpdate { current ->
            if (current >= 255) 1 else current + 1
        }
    }

    fun reset() {
        counter.set(1)
    }
}
