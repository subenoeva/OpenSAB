package com.opensab.app.source.music

import com.opensab.logging.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * Debug-only: answers [DEBUG_MOCK_PACKAGE], and every package while [simulatedBufferingMs] is armed,
 * with a [SimulatedMediaBrowserConnection].
 *
 * **Owner of [simulatedBufferingMs]: `MediaPlaybackDebugReceiver`**, the only writer; it arms it for
 * one broadcast and clears it when the broadcast ends. The graph holds the single instance.
 */
class SimulatedMediaBrowserOverride : MediaBrowserConnectionOverride {

    val simulatedBufferingMs = AtomicReference<Long?>(null)

    override fun connectionFor(packageName: String): MediaBrowserConnection? {
        val bufMs = simulatedBufferingMs.get()
        if (bufMs == null && packageName != DEBUG_MOCK_PACKAGE) return null
        val duration = bufMs ?: 12_000L
        Log.i("SimulatedMediaBrowserOverride: Creating SimulatedMediaBrowserConnection with buffering=$duration ms")
        return SimulatedMediaBrowserConnection(duration)
    }

    companion object {
        const val DEBUG_MOCK_PACKAGE = "com.opensab.debug.mock"
    }
}
