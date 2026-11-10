package com.opensab.sab.session

import com.opensab.sab.session.ble.GattTransport
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicReference

data class LiveSessionLink(
    val session: SabSession,
    val transport: GattTransport,
    val watchdogJob: AtomicReference<Job?> = AtomicReference(null)
)

class LiveSessionLinkHolder {
    private val ref = AtomicReference<LiveSessionLink?>(null)

    val current: LiveSessionLink? get() = ref.get()

    fun set(link: LiveSessionLink) {
        ref.set(link)
    }

    fun clear(link: LiveSessionLink): Boolean =
        ref.compareAndSet(link, null)
}
