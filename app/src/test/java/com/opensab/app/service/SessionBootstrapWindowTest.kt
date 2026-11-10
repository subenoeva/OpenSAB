package com.opensab.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBootstrapWindowTest {

    @Test
    fun anUnlockedScreenThatIsOnNeedsNoFlag() {
        assertEquals(SessionBootstrapWindow(showWhenLocked = false, turnScreenOn = false), SessionBootstrapWindow.of(screenOff = false, locked = false))
    }

    @Test
    fun aScreenOffInThePocketIsWokenAndShownOverTheKeyguard() {
        assertEquals(SessionBootstrapWindow(showWhenLocked = true, turnScreenOn = true), SessionBootstrapWindow.of(screenOff = true, locked = true))
    }

    /**
     * On but locked: behind the keyguard the activity never resumes, and every session would leave
     * through the 5 s fallback without a microphone. The screen is already on, so it is not woken.
     */
    @Test
    fun aLockedScreenThatIsOnIsShownOverTheKeyguardWithoutWakingIt() {
        assertEquals(SessionBootstrapWindow(showWhenLocked = true, turnScreenOn = false), SessionBootstrapWindow.of(screenOff = false, locked = true))
    }
}
