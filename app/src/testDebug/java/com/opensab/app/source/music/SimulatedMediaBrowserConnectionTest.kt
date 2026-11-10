package com.opensab.app.source.music

import org.junit.Assert.assertEquals
import org.junit.Test

class SimulatedMediaBrowserConnectionTest {

    @Test
    fun simulatedMediaBrowserConnectionEmitsBufferingAndTransitionsToPlaying() {
        val states = mutableListOf<Int>()
        var delayedRunnable: Runnable? = null
        val conn = SimulatedMediaBrowserConnection(
            bufferingDurationMs = 5000L,
            postBufferingState = 3,
            dispatcher = { runnable, delayMs ->
                if (delayMs == 0L) {
                    runnable.run()
                } else {
                    delayedRunnable = runnable
                }
            }
        )

        conn.registerPlaybackStateCallback { states.add(it) }
        conn.connect(object : MediaBrowserConnectionCallback {
            override fun onConnectionFailed() {}
            override fun onConnectionSuspended() {}
        })

        // Immediate state is BUFFERING (6)
        assertEquals(listOf(6), states)

        // Delayed state transition fires
        delayedRunnable?.run()
        assertEquals(listOf(6, 3), states)
    }
}
