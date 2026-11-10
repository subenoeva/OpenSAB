package com.opensab.app.service

/**
 * Whether the app has a window the rider can see, as Android judges it when a service asks for the
 * microphone: an activity resumed now, or one that paused less than [graceMs] ago. The grace is
 * what lets the bootstrap activity finish right after sending the START, whose `onStartCommand`
 * lands behind its pause.
 *
 * **Owner: this class, fed only by the activity lifecycle callbacks `OpenSabApplication` registers.**
 */
class AppVisibility(private val clock: () -> Long, private val graceMs: Long) {

    private val lock = Any()
    private var resumed = 0
    private var lastPausedAt: Long? = null

    fun onResumed() {
        synchronized(lock) { resumed++ }
    }

    fun onPaused() {
        synchronized(lock) {
            resumed = (resumed - 1).coerceAtLeast(0)
            lastPausedAt = clock()
        }
    }

    fun isVisible(): Boolean = synchronized(lock) {
        if (resumed > 0) return true
        val paused = lastPausedAt ?: return false
        clock() - paused <= graceMs
    }
}
