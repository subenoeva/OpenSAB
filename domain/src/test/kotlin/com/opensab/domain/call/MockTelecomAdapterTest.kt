package com.opensab.domain.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MockTelecomAdapterTest {

    @Test
    fun telephonyTransitionsCorrectly() {
        val adapter = MockTelecomAdapter()
        assertNull(adapter.currentCall.value)

        adapter.setIncomingCall(CallInfo("Alice", "+34600000000", CallStatus.RINGING))
        assertNotNull(adapter.currentCall.value)
        assertEquals(CallStatus.RINGING, adapter.currentCall.value?.status)

        adapter.acceptCall()
        assertEquals(CallStatus.ACTIVE, adapter.currentCall.value?.status)

        adapter.endCall()
        assertEquals(CallStatus.DISCONNECTED, adapter.currentCall.value?.status)

        adapter.clearCall()
        assertNull(adapter.currentCall.value)
    }
}
