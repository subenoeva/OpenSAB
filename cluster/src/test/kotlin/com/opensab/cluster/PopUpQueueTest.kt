package com.opensab.cluster

import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.session.projection.PopUpPriority
import com.opensab.sab.session.projection.QueuedPopUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PopUpQueueTest {

    @Test
    fun higherPriorityPopUpPreemptsLowerPriority() {
        val queue = PopUpQueue()

        val notification = QueuedPopUp(
            id = "1",
            message = "New Message",
            icon = IconId.INCOMING_MESSAGE,
            priority = PopUpPriority.SYSTEM_NOTIFICATION
        )
        val incomingCall = QueuedPopUp(
            id = "2",
            message = "Incoming Call: Alice",
            icon = IconId.INCOMING_CALL,
            priority = PopUpPriority.INCOMING_CALL
        )

        queue.enqueue(notification)
        queue.enqueue(incomingCall)

        // Incoming call should be dequeued first due to higher priority level
        val first = queue.poll()
        assertNotNull(first)
        assertEquals("2", first?.id)
        assertEquals("Incoming Call: Alice", first?.message)

        val second = queue.poll()
        assertNotNull(second)
        assertEquals("1", second?.id)

        assertNull(queue.poll())
    }
}
