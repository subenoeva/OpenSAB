package com.opensab.sab.session.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopUpPriorityTest {

    @Test
    fun ranksMatchTheReferenceClientWhereLowerMeansMoreUrgent() {
        assertEquals(1, PopUpPriority.INCOMING_CALL.rank)
        assertEquals(100, PopUpPriority.NAVIGATION_ALERT.rank)
    }

    @Test
    fun callOutranksNavigationWhichOutranksNotifications() {
        assertTrue(PopUpPriority.INCOMING_CALL.outranks(PopUpPriority.NAVIGATION_ALERT))
        assertTrue(PopUpPriority.NAVIGATION_ALERT.outranks(PopUpPriority.SYSTEM_NOTIFICATION))
        assertFalse(PopUpPriority.SYSTEM_NOTIFICATION.outranks(PopUpPriority.INCOMING_CALL))
    }

    @Test
    fun aPriorityDoesNotOutrankItself() {
        assertFalse(PopUpPriority.INCOMING_CALL.outranks(PopUpPriority.INCOMING_CALL))
    }
}
