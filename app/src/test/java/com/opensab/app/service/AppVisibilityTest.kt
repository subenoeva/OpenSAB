package com.opensab.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVisibilityTest {

    private var now = 0L
    private val visibility = AppVisibility(clock = { now }, graceMs = 3_000)

    @Test
    fun anAppThatNeverShowedAWindowIsNotVisible() {
        assertFalse(visibility.isVisible())
    }

    @Test
    fun aResumedActivityMakesTheAppVisible() {
        visibility.onResumed()

        assertTrue(visibility.isVisible())
    }

    /** The bootstrap activity finishes right after sending the START: onStartCommand lands after its pause. */
    @Test
    fun theAppStaysVisibleForTheGraceAfterItsLastActivityPauses() {
        visibility.onResumed()
        now = 10_000
        visibility.onPaused()

        now = 13_000
        assertTrue(visibility.isVisible())
        now = 13_001
        assertFalse(visibility.isVisible())
    }

    @Test
    fun oneActivityPausingWhileAnotherIsResumedLeavesTheAppVisible() {
        visibility.onResumed()
        visibility.onResumed()
        visibility.onPaused()
        now = 60_000

        assertTrue(visibility.isVisible())
    }
}
