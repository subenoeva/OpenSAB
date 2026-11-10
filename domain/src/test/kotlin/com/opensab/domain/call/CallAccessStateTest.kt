package com.opensab.domain.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAccessStateTest {

    @Test
    fun onlyTheMissingAccessRowIsActionable() {
        assertTrue(CallAccessState.NEEDS_NOTIFICATION_ACCESS.isActionable)
        assertFalse(CallAccessState.READY.isActionable)
        assertFalse(CallAccessState.UNSUPPORTED.isActionable)
    }

    @Test
    fun everyStateDeclaresWhetherItIsActionable() {
        val expected = mapOf(
            CallAccessState.READY to false,
            CallAccessState.NEEDS_NOTIFICATION_ACCESS to true,
            CallAccessState.UNSUPPORTED to false
        )
        val actual = CallAccessState.entries.associateWith { it.isActionable }
        assertEquals(expected, actual)
    }
}
