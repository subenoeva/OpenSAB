package com.opensab.cluster

import com.opensab.sab.session.projection.QueuedPopUp
import java.util.PriorityQueue

/**
 * Higher priority popups preempt lower priority ones; ties are broken by creation order, and by
 * arrival when two were created in the same millisecond: a heap is not stable on its own.
 */
class PopUpQueue {

    private class Entry(val popUp: QueuedPopUp, val arrival: Long)

    private val queue = PriorityQueue<Entry>(
        compareBy<Entry>({ it.popUp.priority.rank }, { it.popUp.createdAt }, { it.arrival })
    )

    private val lock = Any()
    private var arrivals = 0L

    fun enqueue(popUp: QueuedPopUp) {
        synchronized(lock) {
            queue.offer(Entry(popUp, arrivals++))
        }
    }

    fun poll(): QueuedPopUp? {
        synchronized(lock) {
            return queue.poll()?.popUp
        }
    }

    fun peek(): QueuedPopUp? {
        synchronized(lock) {
            return queue.peek()?.popUp
        }
    }

    val size: Int
        get() = synchronized(lock) { queue.size }

    fun clear() {
        synchronized(lock) {
            queue.clear()
        }
    }
}
