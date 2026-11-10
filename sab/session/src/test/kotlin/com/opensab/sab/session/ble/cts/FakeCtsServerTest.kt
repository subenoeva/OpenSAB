package com.opensab.sab.session.ble.cts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeCtsServerTest {

    @Test
    fun fakeCtsServerLifecycle() {
        val server = FakeCtsServer()
        assertEquals(false, server.isRunning())

        assertTrue(server.start())
        assertEquals(true, server.isRunning())

        server.notifyTimeUpdated()
        assertEquals(1, server.notificationCount)

        server.stop()
        assertEquals(false, server.isRunning())
    }
}
