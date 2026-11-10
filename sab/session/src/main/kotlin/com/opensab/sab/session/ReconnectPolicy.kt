package com.opensab.sab.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How many times a failed connection is tried again, and how long each retry waits: one entry of
 * [backoff] per retry, in order. Applied by [SabSessionController], which owns the retry.
 */
class ReconnectPolicy(private val backoff: List<Duration>) {

    val maxRetries: Int get() = backoff.size

    /** The wait before retry number [retry] (1-based), or null once the retries are spent. */
    fun delayBefore(retry: Int): Duration? = backoff.getOrNull(retry - 1)

    companion object {
        /** One attempt and no more. For harnesses that are not about the retry. */
        val NONE = ReconnectPolicy(emptyList())

        /**
         * Three retries, 17 s of waiting at most. Chosen, not measured: on 2026-10-02 the motorcycle
         * stayed linked 11 s after the only attempt failed, and an attempt against a silent link
         * takes 10 s by itself (two 5 s GATT watchdogs).
         */
        val DEFAULT = ReconnectPolicy(listOf(2.seconds, 5.seconds, 10.seconds))
    }
}

/**
 * Whether a motorcycle is still linked to the phone, as far as the phone has been told. It is what
 * makes a retry honest: a timeout alone does not say the motorcycle is there.
 */
fun interface VehiclePresence {
    /** Emits the current answer at once and every change after it. */
    fun linked(macAddress: String): Flow<Boolean>

    companion object {
        /** Nothing is known, so nothing is retried. */
        val Unknown = VehiclePresence { flowOf(false) }
    }
}
