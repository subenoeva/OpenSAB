package com.opensab.logging.journal

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A [Clock] the test moves by hand. */
class MutableClock(private var current: Instant = Instant.EPOCH) : Clock() {
    fun set(iso: String) {
        current = Instant.parse(iso)
    }

    fun set(instant: Instant) {
        current = instant
    }

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }

    override fun instant(): Instant = current
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
