package com.opensab.cluster

import com.opensab.sab.session.projection.QueuedPopUp
import java.util.PriorityQueue

/** Higher priority popups preempt lower priority ones; ties are broken by creation order. */
class PopUpQueue {

    private val queue = PriorityQueue<QueuedPopUp> { a, b ->
        val priorityDiff = a.priority.rank.compareTo(b.priority.rank)
        if (priorityDiff != 0) priorityDiff else a.createdAt.compareTo(b.createdAt)
    }

    private val lock = Any()

    fun enqueue(popUp: QueuedPopUp) {
        synchronized(lock) {
            queue.offer(popUp)
        }
    }

    fun poll(): QueuedPopUp? {
        synchronized(lock) {
            return queue.poll()
        }
    }

    fun peek(): QueuedPopUp? {
        synchronized(lock) {
            return queue.peek()
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
