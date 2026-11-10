package com.opensab.app.source.music

import android.os.Handler
import android.os.Looper

class SimulatedMediaBrowserConnection(
    private val bufferingDurationMs: Long,
    private val postBufferingState: Int = 3,
    private val dispatcher: ((runnable: Runnable, delayMs: Long) -> Unit)? = null
) : MediaBrowserConnection {

    override val rootId: String = "simulated-root"
    private var stateCallback: ((Int) -> Unit)? = null
    private var isDisconnected = false
    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var bufferingRunnable: Runnable? = null

    private fun postAction(delayMs: Long, block: () -> Unit) {
        val runnable = Runnable { block() }
        if (dispatcher != null) {
            dispatcher.invoke(runnable, delayMs)
        } else {
            if (delayMs == 0L) {
                handler.post(runnable)
            } else {
                bufferingRunnable = runnable
                handler.postDelayed(runnable, delayMs)
            }
        }
    }

    override fun connect(callback: MediaBrowserConnectionCallback) {
        postAction(0L) {
            if (isDisconnected) return@postAction
            callback.onConnected(BrowserReady(rootId = rootId, initialState = 6))
            stateCallback?.invoke(6)

            postAction(bufferingDurationMs) {
                if (!isDisconnected) {
                    stateCallback?.invoke(postBufferingState)
                }
            }
        }
    }

    override fun disconnect() {
        isDisconnected = true
        bufferingRunnable?.let { handler.removeCallbacks(it) }
        bufferingRunnable = null
    }

    override fun subscribe(parentId: String, callback: MediaBrowserSubscriptionCallback) {
        postAction(0L) {
            if (!isDisconnected) {
                callback.onChildrenLoaded(
                    parentId,
                    listOf(MediaBrowserItem("sim-item-1", isPlayable = true, isBrowsable = false))
                )
            }
        }
    }

    override fun unsubscribe(parentId: String) {}

    override fun play() {}

    override fun playFromMediaId(mediaId: String) {}

    override fun registerPlaybackStateCallback(callback: (Int) -> Unit) {
        stateCallback = callback
    }
}
