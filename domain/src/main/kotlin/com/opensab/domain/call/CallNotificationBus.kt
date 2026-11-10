package com.opensab.domain.call

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription

/**
 * The pipe from `OpenSabNotificationListenerService` (`:app`) to [DefaultCallManager]. Built only by
 * `NotificationModule.provideCallNotificationBus`. The live call itself — status, identity,
 * timestamp, focus — is owned by the source that reads this; what the bus keeps is only which call
 * notifications are in the shade right now.
 *
 * It hands a late collector something, and its two siblings do not. That is deliberate: a message
 * is an **event** — handing it over later would read the rider something that already happened —
 * while a live call is a **state**. Connecting the motorcycle mid-call has to light the PHONE bit
 * and mount the page, and the source only starts when the session does.
 *
 * So the bus keeps that state and not the history that led to it: the newest
 * [CallNotificationEvent.Posted] of every live key. A `Posted` replaces the entry of its key, a
 * [CallNotificationEvent.Removed] drops it and [CallNotificationEvent.ListenerDisconnected] drops
 * them all. A new collector of [events] is handed those entries, in the order their newest posts
 * arrived, and then every event posted after it subscribed, each exactly once.
 *
 * A window of past events is what this replaces, and it was wrong both ways. A call that ended an
 * hour ago was still in it, so a session starting later rebuilt that dead call — ringing, gone,
 * active, gone — lighting PHONE and taking the audio focus for nobody. And the edge posts a
 * `Removed` for every notification that is not a call, so a phone's ordinary traffic pushed a call
 * that was still ringing out of the window. The state has neither problem: an ended call is not in
 * it, and no amount of other notifications can bury a live one.
 *
 * **Owner of [lock]: this bus.** It guards [live] and [sequence], and every post forwards its event
 * while holding it, so the order of [stream] is the order of [sequence] and a subscriber's snapshot
 * falls exactly between two posts. Forwarding after releasing the lock would let two concurrent
 * posters hand their events over in the opposite order to their stamps, and a collector would apply
 * a `Posted` and its `Removed` backwards. Holding it is safe: the forward never suspends (see
 * [stream]), and the one collector, `DefaultCallManager`, runs on a dispatched session thread, so
 * it is resumed there and never runs inside the lock. Even an inline dispatcher could not deadlock
 * it — the lock is reentrant and a collector does not post.
 */
class CallNotificationBus {

    private class Stamped(val sequence: Long, val event: CallNotificationEvent)

    private val lock = Any()

    /** The newest post of every live key, in the order those posts arrived. Guarded by [lock]. */
    private val live = LinkedHashMap<String, CallNotificationEvent.Posted>()

    /** The number of events posted so far. Guarded by [lock]. */
    private var sequence = 0L

    /**
     * Live forwarding only: what a late collector needs is in [live], so nothing is replayed.
     *
     * **Unbounded, so `tryEmit` never fails.** The edge posts a `Removed` for every notification
     * that is not a call and the collector shares the session's single thread, so it can fall far
     * behind; with a bounded buffer the post that overflows it is dropped, and a dropped `Removed`
     * of the dialer's key pins a dead call — PHONE lit, page up, audio focus held — for the whole
     * session. What it buffers is only what one busy collector has not read yet.
     */
    private val stream = MutableSharedFlow<Stamped>(extraBufferCapacity = Int.MAX_VALUE)

    val events: Flow<CallNotificationEvent> = flow {
        var fence = Long.MAX_VALUE
        stream
            .onSubscription {
                // Subscribed already, so every post from here on is buffered for this collector;
                // the fence drops the ones the snapshot below already accounts for.
                val snapshot = synchronized(lock) {
                    fence = sequence
                    live.values.toList()
                }
                snapshot.forEach { this@flow.emit(it) }
            }
            .collect { if (it.sequence > fence) emit(it.event) }
    }

    fun post(event: CallNotificationEvent) {
        synchronized(lock) {
            when (event) {
                is CallNotificationEvent.Posted -> {
                    // Re-inserted so the order is that of the newest post, which is the order the
                    // source would have seen them become current in.
                    live.remove(event.notification.key)
                    live[event.notification.key] = event
                }
                is CallNotificationEvent.Removed -> live.remove(event.key)
                CallNotificationEvent.ListenerDisconnected -> live.clear()
            }
            sequence++
            stream.tryEmit(Stamped(sequence, event))
        }
    }
}
