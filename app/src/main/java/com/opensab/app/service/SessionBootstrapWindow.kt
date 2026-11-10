package com.opensab.app.service

/**
 * The window flags [SessionBootstrapActivity] needs to count as visible. Over a keyguard, locked or
 * with the screen off, an activity that is not shown when locked never resumes, and the START would
 * leave through the fallback without a microphone. The screen is only woken if it was off.
 */
data class SessionBootstrapWindow(val showWhenLocked: Boolean, val turnScreenOn: Boolean) {
    companion object {
        fun of(screenOff: Boolean, locked: Boolean) =
            SessionBootstrapWindow(showWhenLocked = screenOff || locked, turnScreenOn = screenOff)
    }
}
