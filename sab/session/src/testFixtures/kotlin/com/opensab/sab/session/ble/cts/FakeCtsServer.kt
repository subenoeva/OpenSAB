package com.opensab.sab.session.ble.cts

class FakeCtsServer : CtsServer {
    var isStarted: Boolean = false
    var notificationCount: Int = 0

    override fun start(): Boolean {
        isStarted = true
        return true
    }

    override fun stop() {
        isStarted = false
    }

    override fun isRunning(): Boolean = isStarted

    override fun notifyTimeUpdated() {
        if (isStarted) {
            notificationCount++
        }
    }
}
