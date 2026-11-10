package com.opensab.domain.speech

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothVoiceRouterTest {

    @Test
    fun mockRouterTracksRoutingLifecycle() = runTest {
        val router = MockBluetoothVoiceRouter()
        assertFalse(router.isHeadsetConnected)
        assertFalse(router.isRoutingActive)
        assertEquals(0, router.startCalls)
        assertEquals(0, router.stopCalls)

        router.isHeadsetConnected = true

        val started = router.startVoiceRouting()
        assertTrue(started)
        assertTrue(router.isRoutingActive)
        assertEquals(1, router.startCalls)

        router.stopVoiceRouting()
        assertFalse(router.isRoutingActive)
        assertEquals(1, router.stopCalls)
    }

    @Test
    fun mockRouterReturnsFalseWhenNoHeadsetConnected() = runTest {
        val router = MockBluetoothVoiceRouter()
        router.isHeadsetConnected = false

        val started = router.startVoiceRouting()
        assertFalse(started)
        assertFalse(router.isRoutingActive)
        assertEquals(1, router.startCalls)
    }
}
